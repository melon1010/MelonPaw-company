package com.melon.app.runner;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import com.melon.core.agent.MultiAgentManager;
import com.melon.app.service.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.reactivestreams.Subscription;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * 请求→Agent 调用编排. 对应 Python runner/runner.py 的 query_handler.
 * 区别: 不新建 Agent, 复用单例; AgentScope 自动管理 state load/save.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final MultiAgentManager multiAgentManager;
    private final ApprovalService approvalService;
    private final ConcurrentMap<String, java.util.Set<Subscription>> activeExecutions = new ConcurrentHashMap<>();

    public AgentRunner(MultiAgentManager multiAgentManager, ApprovalService approvalService) {
        this.multiAgentManager = multiAgentManager;
        this.approvalService = approvalService;
    }

    /**
     * 流式查询. 对应 Python _stream_printing_messages_interruptible.
     */
    public Flux<AgentEvent> stream(String agentId, List<Msg> msgs,
                                    String userId, String sessionId,
                                    Map<String, Object> envInfo) {
        log.info("Stream query: agent={}, user={}, session={}", agentId, userId, sessionId);

        String executionKey = executionKey(agentId, userId, sessionId);
        return Flux.defer(() -> {
                    AtomicReference<Subscription> tracked = new AtomicReference<>();
                    HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
                    RuntimeContext ctx = buildContext(agentId, userId, sessionId, envInfo);
                    return streamWithApprovals(agent, msgs, ctx, agentId, sessionId)
                            .doOnSubscribe(subscription -> {
                                tracked.set(subscription);
                                registerExecution(executionKey, subscription);
                            })
                            .doFinally(signal -> unregisterExecution(executionKey, tracked.get()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Agent stream failed: agent={}, user={}, session={}", agentId, userId, sessionId, e));
    }

    /**
     * 非流式查询.
     */
    public Mono<Msg> query(String agentId, List<Msg> msgs,
                            String userId, String sessionId,
                            Map<String, Object> envInfo) {
        log.info("Query: agent={}, user={}, session={}", agentId, userId, sessionId);

        String executionKey = executionKey(agentId, userId, sessionId);
        return Mono.defer(() -> {
                    AtomicReference<Subscription> tracked = new AtomicReference<>();
                    HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
                    RuntimeContext ctx = buildContext(agentId, userId, sessionId, envInfo);
                    return agent.call(msgs, ctx)
                            .doOnSubscribe(subscription -> {
                                tracked.set(subscription);
                                registerExecution(executionKey, subscription);
                            })
                            .doFinally(signal -> unregisterExecution(executionKey, tracked.get()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Cancel the active Web/channel/automation execution for one authenticated scope. */
    public boolean cancel(String agentId, String userId, String sessionId) {
        java.util.Set<Subscription> subscriptions = activeExecutions.remove(executionKey(agentId, userId, sessionId));
        if (subscriptions == null || subscriptions.isEmpty()) {
            return false;
        }
        subscriptions.forEach(Subscription::cancel);
        return true;
    }

    private void registerExecution(String key, Subscription subscription) {
        activeExecutions.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(subscription);
    }

    private void unregisterExecution(String key, Subscription subscription) {
        if (subscription == null) return;
        java.util.Set<Subscription> subscriptions = activeExecutions.get(key);
        if (subscriptions == null) return;
        subscriptions.remove(subscription);
        if (subscriptions.isEmpty()) activeExecutions.remove(key, subscriptions);
    }

    private String executionKey(String agentId, String userId, String sessionId) {
        return value(agentId, "default") + "\u0000" + value(userId, "default")
                + "\u0000" + value(sessionId, "default");
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    RuntimeContext buildContext(String agentId, String userId, String sessionId,
                                Map<String, Object> envInfo) {
        String sid = sessionId != null ? sessionId : "default";
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(sid)
                .userId(userId);

        builder.put("session_id", sid);
        builder.put("root_session_id", sid);
        builder.put("agent_id", value(agentId, "default"));
        builder.put("root_agent_id", value(agentId, "default"));
        if (userId != null) {
            builder.put("user_id", userId);
        }

        if (envInfo != null) {
            envInfo.forEach((key, value) -> {
                if (key != null && value != null && !isReservedContextKey(key)) {
                    builder.put(key, value);
                }
            });
        }

        return builder.build();
    }

    private Flux<AgentEvent> streamWithApprovals(HarnessAgent agent, List<Msg> msgs, RuntimeContext ctx,
                                                 String agentId, String sessionId) {
        String sid = sessionId != null ? sessionId : "default";
        AtomicReference<Mono<List<ConfirmResult>>> pending = new AtomicReference<>();
        return Flux.defer(() -> agent.streamEvents(msgs, ctx))
                .subscribeOn(Schedulers.boundedElastic())
                .<AgentEvent>handle((event, sink) -> {
                    if (event instanceof RequireUserConfirmEvent confirm) {
                        List<Map<String, Object>> requests = confirm.getToolCalls().stream()
                                .map(toolCall -> normalizeApproval(agentId, sid, ctx, toolCall))
                                .toList();
                        String ownerUserId = ctx != null && ctx.getUserId() != null
                                ? ctx.getUserId() : "";
                        pending.set(approvalService.openPendingApproval(
                                agentId, sid, ownerUserId, confirm.getToolCalls(), requests));
                        sink.next(event);
                        return;
                    }
                    if (pending.get() != null && isPermissionStopEvent(event)) {
                        return;
                    }
                    sink.next(event);
                })
                .concatWith(Flux.defer(() -> {
                    Mono<List<ConfirmResult>> wait = pending.get();
                    if (wait == null) return Flux.empty();
                    return wait.publishOn(Schedulers.boundedElastic()).flatMapMany(results -> {
                        Msg confirm = UserMessage.builder()
                                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                                .build();
                        return streamWithApprovals(agent, List.of(confirm), ctx, agentId, sid);
                    });
                }));
    }

    private boolean isPermissionStopEvent(AgentEvent event) {
        AgentEventType type = event.getType();
        return type == AgentEventType.REQUEST_STOP || type == AgentEventType.AGENT_END;
    }

    private Map<String, Object> normalizeApproval(String agentId, String sessionId, RuntimeContext ctx, ToolUseBlock toolCall) {
        String sid = sessionId != null ? sessionId : "default";
        Object rootRaw = ctx != null ? ctx.get("root_session_id") : null;
        String rootSid = rootRaw != null && !String.valueOf(rootRaw).isBlank() ? String.valueOf(rootRaw) : sid;
        Object ownerRaw = ctx != null ? ctx.get("root_agent_id") : null;
        String aid = stringValue(agentId, "default");
        String ownerAid = ownerRaw != null && !String.valueOf(ownerRaw).isBlank() ? String.valueOf(ownerRaw) : aid;
        String displayToolName = FrontendToolCompat.displayToolName(toolCall.getName());
        Map<String, Object> approval = new java.util.LinkedHashMap<>();
        approval.put("request_id", toolCall.getId());
        approval.put("session_id", sid);
        approval.put("root_session_id", rootSid);
        approval.put("agent_id", aid);
        String userId = ctx != null ? ctx.getUserId() : null;
        if (userId != null && !userId.isBlank()) {
            approval.put("owner_user_id", userId);
        }
        approval.put("owner_agent_id", ownerAid);
        approval.put("tool_name", displayToolName);
        approval.put("tool_display_name", displayToolName);
        approval.put("actual_tool_name", toolCall.getName());
        approval.put("tool_source", "agentscope");
        approval.put("severity", "medium");
        approval.put("findings_count", 1);
        approval.put("findings_summary", "AgentScope requires approval before running this tool.");
        approval.put("tool_params", toolCall.getInput());
        approval.put("created_at", System.currentTimeMillis() / 1000.0);
        approval.put("timeout_seconds", 300);
        return approval;
    }

    /** Request metadata must not be able to replace the authenticated execution identity. */
    private boolean isReservedContextKey(String key) {
        return switch (key) {
            case "user_id", "session_id", "agent_id", "root_session_id", "root_agent_id",
                    "working_dir", "project_dir" -> true;
            default -> false;
        };
    }

}
