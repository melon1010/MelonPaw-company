package com.melon.core.middleware;

import com.melon.core.util.JsonUtils;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Stops one invocation after the same tool call fails repeatedly with identical arguments. */
public final class ToolFailureGuardMiddleware implements MiddlewareBase {

    private static final int DEFAULT_MAX_IDENTICAL_FAILURES = 2;
    private final int maxIdenticalFailures;

    public ToolFailureGuardMiddleware() {
        this(DEFAULT_MAX_IDENTICAL_FAILURES);
    }

    ToolFailureGuardMiddleware(int maxIdenticalFailures) {
        this.maxIdenticalFailures = Math.max(1, maxIdenticalFailures);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (ctx == null || input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        FailureState state = ctx.get(FailureState.class);
        if (state == null) {
            state = new FailureState();
            ctx.put(FailureState.class, state);
        }

        Map<String, ToolFingerprint> calls = new LinkedHashMap<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (call == null || call.getId() == null) continue;
            calls.put(call.getId(), fingerprint(call));
        }
        FailureState invocationState = state;
        AtomicReference<String> stopReason = new AtomicReference<>();

        return next.apply(input)
                .doOnNext(event -> {
                    if (!(event instanceof ToolResultEndEvent ended)) return;
                    ToolFingerprint fingerprint = calls.get(ended.getToolCallId());
                    if (fingerprint == null) return;
                    ToolResultState resultState = ended.getState();
                    if (resultState == ToolResultState.SUCCESS) {
                        invocationState.clear(fingerprint);
                        return;
                    }
                    if (resultState != ToolResultState.ERROR
                            && resultState != ToolResultState.INTERRUPTED) {
                        return;
                    }
                    int failures = invocationState.recordFailure(fingerprint);
                    if (failures >= maxIdenticalFailures) {
                        stopReason.compareAndSet(null,
                                "Stopped after " + failures + " identical failures from tool '"
                                        + fingerprint.toolName() + "'. Change the approach or inputs before retrying.");
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String reason = stopReason.get();
                    if (reason == null) return Flux.empty();
                    return Flux.just(new RequestStopEvent(reason, GenerateReason.MIDDLEWARE_STOP_REQUESTED));
                }));
    }

    private ToolFingerprint fingerprint(ToolUseBlock call) {
        String name = call.getName() == null ? "unknown" : call.getName().trim().toLowerCase();
        return new ToolFingerprint(name, JsonUtils.toJson(canonical(call.getInput())));
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            iterable.forEach(item -> items.add(canonical(item)));
            return items;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) items.add(canonical(java.lang.reflect.Array.get(value, i)));
            return items;
        }
        return value;
    }

    private record ToolFingerprint(String toolName, String arguments) {
    }

    private static final class FailureState {
        private final Map<ToolFingerprint, Integer> failures = new ConcurrentHashMap<>();

        int recordFailure(ToolFingerprint fingerprint) {
            return failures.merge(fingerprint, 1, Integer::sum);
        }

        void clear(ToolFingerprint fingerprint) {
            failures.remove(fingerprint);
        }
    }
}
