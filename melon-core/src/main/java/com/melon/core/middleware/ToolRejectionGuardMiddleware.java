package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Stops the current reasoning loop after a permission rejection. */
public final class ToolRejectionGuardMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        AtomicBoolean rejected = new AtomicBoolean();
        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof AllToolsDeniedEvent) {
                        rejected.set(true);
                    } else if (event instanceof ToolResultEndEvent result
                            && result.getState() == ToolResultState.DENIED) {
                        rejected.set(true);
                    }
                })
                .concatWith(Flux.defer(() -> rejected.get()
                        ? Flux.just(new RequestStopEvent(
                                "Stopped after the user or policy denied a tool operation.",
                                GenerateReason.ALL_TOOLS_DENIED))
                        : Flux.empty()));
    }
}
