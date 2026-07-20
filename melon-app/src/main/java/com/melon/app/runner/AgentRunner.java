package com.melon.app.runner;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.harness.agent.HarnessAgent;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.SubagentTaskCoordinator;
import com.melon.core.config.ConfigManager;
import com.melon.app.service.ApprovalService;
import com.melon.app.mcp.McpToolkitContributor;
import com.melon.app.task.EnterpriseQuotaService;
import com.melon.app.task.EnterpriseTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import org.reactivestreams.Subscription;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Locale;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
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
    private final ConfigManager configManager;
    private final ToolRiskClassifier toolRiskClassifier;
    private final EnterpriseTaskService taskService;
    private final ConcurrentMap<String, java.util.Set<Subscription>> activeExecutions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EnterpriseTaskService.Delegation> activeDelegations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EnterpriseTaskService.Delegation> backgroundDelegations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> delegationRoles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StringBuilder> toolResultBuffers = new ConcurrentHashMap<>();

    public AgentRunner(MultiAgentManager multiAgentManager, ApprovalService approvalService) {
        this(multiAgentManager, approvalService, null, new ToolRiskClassifier(), null);
    }

    @Autowired
    public AgentRunner(MultiAgentManager multiAgentManager, ApprovalService approvalService,
                       ConfigManager configManager, ToolRiskClassifier toolRiskClassifier,
                       EnterpriseTaskService taskService) {
        this.multiAgentManager = multiAgentManager;
        this.approvalService = approvalService;
        this.configManager = configManager;
        this.toolRiskClassifier = toolRiskClassifier != null ? toolRiskClassifier : new ToolRiskClassifier();
        this.taskService = taskService;
    }

    public enum LoopMode { GOAL, MISSION }

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
                    return enforceRuntimeBudget(agent,
                            streamWithApprovals(agent, msgs, ctx, agentId, sessionId), ctx)
                            .doOnNext(event -> recordTaskEvent(ctx, event))
                            .doOnSubscribe(subscription -> {
                                tracked.set(subscription);
                                registerExecution(executionKey, subscription);
                            })
                            .doFinally(signal -> {
                                unregisterExecution(executionKey, tracked.get());
                                finishExecution(agent, ctx, signal);
                            });
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Agent stream failed: agent={}, user={}, session={}", agentId, userId, sessionId, e));
    }

    /** Runs a real multi-turn goal or mission loop with a model-based completion rubric. */
    public Flux<AgentEvent> streamLoop(String agentId, LoopMode mode, String objective,
                                       String userId, String sessionId,
                                       Map<String, Object> envInfo) {
        String executionKey = executionKey(agentId, userId, sessionId);
        return Flux.defer(() -> {
                    AtomicReference<Subscription> tracked = new AtomicReference<>();
                    HarnessAgent agent = multiAgentManager.getOrCreate(agentId);
                    RuntimeContext ctx = buildContext(agentId, userId, sessionId, envInfo);
                    LoopSettings settings = loopSettings(agentId);
                    return enforceRuntimeBudget(agent,
                            streamLoopIteration(agent, mode, objective, ctx, agentId,
                                    sessionId, 1, settings, null, List.of(), 0), ctx)
                            .doOnNext(event -> recordTaskEvent(ctx, event))
                            .doOnSubscribe(subscription -> {
                                tracked.set(subscription);
                                registerExecution(executionKey, subscription);
                            })
                            .doFinally(signal -> {
                                unregisterExecution(executionKey, tracked.get());
                                finishExecution(agent, ctx, signal);
                            });
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Agent loop failed: mode={}, agent={}, session={}",
                        mode, agentId, sessionId, e));
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
                    return enforceRuntimeBudget(agent,
                            streamWithApprovals(agent, msgs, ctx, agentId, sessionId), ctx)
                            .doOnNext(event -> recordTaskEvent(ctx, event))
                            .ofType(AgentResultEvent.class)
                            .map(AgentResultEvent::getResult)
                            .last()
                            .doOnSubscribe(subscription -> {
                                tracked.set(subscription);
                                registerExecution(executionKey, subscription);
                            })
                            .doFinally(signal -> {
                                unregisterExecution(executionKey, tracked.get());
                                finishExecution(agent, ctx, signal);
                            });
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AgentEvent> enforceRuntimeBudget(
            HarnessAgent agent, Flux<AgentEvent> source, RuntimeContext ctx) {
        if (taskService == null || ctx == null) return source;
        String taskId = stringValue(ctx.get("task_id"));
        String runId = stringValue(ctx.get("run_id"));
        if (taskId.isBlank() || runId.isBlank()) return source;
        long seconds = taskService.remainingRuntimeSeconds(taskId, runId);
        if (seconds <= 0) return source;
        return source.timeout(Duration.ofSeconds(seconds))
                .onErrorMap(TimeoutException.class, error -> {
                    String message = "Task exceeded its " + seconds + " second remaining runtime budget";
                    SubagentTaskCoordinator.cancelRunning(agent, ctx);
                    taskService.timeout(taskId, runId, message);
                    return new EnterpriseQuotaService.QuotaExceededException(
                            "TASK", "max_runtime_seconds", message);
                });
    }

    /** Cancel the active Web/channel/automation execution for one authenticated scope. */
    public boolean cancel(String agentId, String userId, String sessionId) {
        HarnessAgent agent = multiAgentManager.getAgent(agentId);
        RuntimeContext context = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        boolean tasksCancelled = agent != null
                && !SubagentTaskCoordinator.cancelRunning(agent, context).isEmpty();
        java.util.Set<Subscription> subscriptions = activeExecutions.remove(executionKey(agentId, userId, sessionId));
        if (subscriptions == null || subscriptions.isEmpty()) {
            return tasksCancelled;
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
        return streamWithApprovals(agent, msgs, ctx, agentId, sessionId, true);
    }

    private Flux<AgentEvent> streamWithApprovals(HarnessAgent agent, List<Msg> msgs, RuntimeContext ctx,
                                                 String agentId, String sessionId,
                                                 boolean recoverOrphanedApprovals) {
        String sid = sessionId != null ? sessionId : "default";
        AtomicReference<Mono<List<ConfirmResult>>> pending = new AtomicReference<>();
        return Flux.defer(() -> {
                    if (!recoverOrphanedApprovals) {
                        // A live approval decision must reach AgentScope unchanged. Re-running
                        // orphan recovery here would classify ASK again and turn approval into denial.
                        return agent.streamEvents(msgs, ctx);
                    }
                    List<ToolUseBlock> staleAsking = pendingAsking(agent, ctx);
                    if (staleAsking.isEmpty()) return agent.streamEvents(msgs, ctx);
                    List<ConfirmResult> recovered = recoverPendingConfirmations(agent, staleAsking, ctx, agentId);
                    Msg confirmation = UserMessage.builder()
                            .name("__internal__")
                            .metadata(ConversationVisibility.modelOnlyMetadata(
                                    Map.of(Msg.METADATA_CONFIRM_RESULTS, recovered), "approval_recovery"))
                            .build();
                    // A disconnected client can leave an ASKING tool call in the persisted
                    // AgentScope state. Resolve that orphan first, then process the new input.
                    return agent.streamEvents(List.of(confirmation), ctx)
                            .filter(event -> !isPermissionStopEvent(event))
                            .concatWith(Flux.defer(() -> agent.streamEvents(msgs, ctx)));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .<AgentEvent>handle((event, sink) -> {
                    if (event instanceof RequireUserConfirmEvent confirm) {
                        String approvalMode = approvalService != null
                                ? approvalService.getApprovalMode(agentId) : "RISK_BASED";
                        boolean strict = "STRICT".equalsIgnoreCase(approvalMode);
                        boolean disabled = "OFF".equalsIgnoreCase(approvalMode);
                        List<ToolUseBlock> approvalCalls = new ArrayList<>();
                        List<Map<String, Object>> requests = new ArrayList<>();
                        List<ConfirmResult> immediate = new ArrayList<>();
                        for (ToolUseBlock toolCall : confirm.getToolCalls()) {
                            if ("agent_spawn".equalsIgnoreCase(toolCall.getName())) {
                                String role = stringValue(toolCall.getInput() != null
                                        ? toolCall.getInput().get("role") : null);
                                if (role.isBlank()) role = stringValue(toolCall.getInput() != null
                                        ? toolCall.getInput().get("agent_id") : null);
                                String contextTaskId = stringValue(ctx != null ? ctx.get("task_id") : null);
                                if (!role.isBlank() && toolCall.getId() != null && !contextTaskId.isBlank()) {
                                    delegationRoles.put(delegationKey(contextTaskId, toolCall.getId()), role);
                                }
                            }
                            String mcpEffect = mcpAccessEffect(agent, toolCall.getName(), ctx);
                            ToolRiskClassifier.Assessment assessment = switch (mcpEffect) {
                                case "deny" -> toolRiskClassifier.forceDeny(toolCall);
                                case "ask" -> toolRiskClassifier.forceStrict(toolCall);
                                default -> disabled
                                        ? toolRiskClassifier.forceAllow(toolCall)
                                        : (strict ? toolRiskClassifier.forceStrict(toolCall)
                                                  : toolRiskClassifier.classify(toolCall,
                                                          isMcpTool(agent, toolCall.getName()),
                                                          isReadOnlyTool(agent, toolCall.getName())));
                            };
                            switch (assessment.decision()) {
                                case ALLOW -> immediate.add(new ConfirmResult(true, toolCall));
                                case DENY -> immediate.add(new ConfirmResult(false, toolCall));
                                case ASK -> {
                                    approvalCalls.add(toolCall);
                                    requests.add(normalizeApproval(agentId, sid, ctx, toolCall, assessment));
                                }
                            }
                        }
                        String ownerUserId = ctx != null && ctx.getUserId() != null
                                ? ctx.getUserId() : "";
                        if (approvalCalls.isEmpty()) {
                            pending.set(Mono.just(List.copyOf(immediate)));
                        } else {
                            Mono<List<ConfirmResult>> reviewed = approvalService.openPendingApproval(
                                    agentId, sid, ownerUserId, approvalCalls, requests);
                            pending.set(reviewed.map(results -> {
                                List<ConfirmResult> merged = new ArrayList<>(immediate);
                                merged.addAll(results);
                                return List.copyOf(merged);
                            }));
                            sink.next(new RequireUserConfirmEvent(confirm.getReplyId(), approvalCalls));
                        }
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
                                .name("__internal__")
                                .metadata(ConversationVisibility.modelOnlyMetadata(
                                        Map.of(Msg.METADATA_CONFIRM_RESULTS, results), "approval_resolution"))
                                .build();
                        return streamWithApprovals(agent, List.of(confirm), ctx, agentId, sid, false);
                    });
                }));
    }

    private List<ToolUseBlock> pendingAsking(HarnessAgent agent, RuntimeContext ctx) {
        if (agent == null || agent.getDelegate() == null) return List.of();
        List<Msg> context = agent.getDelegate().getAgentState(ctx).getContext();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            List<ToolUseBlock> asking = message.getContentBlocks(ToolUseBlock.class).stream()
                    .filter(call -> call.getState() == io.agentscope.core.message.ToolCallState.ASKING)
                    .toList();
            if (!asking.isEmpty()) return asking;
            if (message.getRole() == io.agentscope.core.message.MsgRole.ASSISTANT) return List.of();
        }
        return List.of();
    }

    private List<ConfirmResult> recoverPendingConfirmations(HarnessAgent agent,
                                                              List<ToolUseBlock> calls,
                                                              RuntimeContext ctx,
                                                              String agentId) {
        String mode = approvalService != null ? approvalService.getApprovalMode(agentId) : "RISK_BASED";
        boolean strict = "STRICT".equalsIgnoreCase(mode);
        boolean disabled = "OFF".equalsIgnoreCase(mode);
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            String mcpEffect = mcpAccessEffect(agent, call.getName(), ctx);
            ToolRiskClassifier.Assessment assessment = switch (mcpEffect) {
                case "deny" -> toolRiskClassifier.forceDeny(call);
                case "ask" -> toolRiskClassifier.forceStrict(call);
                default -> disabled
                        ? toolRiskClassifier.forceAllow(call)
                        : (strict ? toolRiskClassifier.forceStrict(call)
                                  : toolRiskClassifier.classify(call,
                                          isMcpTool(agent, call.getName()),
                                          isReadOnlyTool(agent, call.getName())));
            };
            results.add(new ConfirmResult(assessment.decision() == ToolRiskClassifier.Decision.ALLOW, call));
        }
        return List.copyOf(results);
    }

    private boolean isPermissionStopEvent(AgentEvent event) {
        AgentEventType type = event.getType();
        return type == AgentEventType.REQUEST_STOP || type == AgentEventType.AGENT_END;
    }

    private boolean isMcpTool(HarnessAgent agent, String toolName) {
        if (agent == null || agent.getToolkit() == null || toolName == null) return false;
        return agent.getToolkit().getTool(toolName) instanceof ToolBase tool && tool.isMcp();
    }

    private boolean isReadOnlyTool(HarnessAgent agent, String toolName) {
        if (agent == null || agent.getToolkit() == null || toolName == null) return false;
        return agent.getToolkit().getTool(toolName) instanceof ToolBase tool && tool.isReadOnly();
    }

    private String mcpAccessEffect(HarnessAgent agent, String toolName, RuntimeContext context) {
        if (agent == null || agent.getToolkit() == null || toolName == null) return "";
        ToolBase tool = agent.getToolkit().getTool(toolName) instanceof ToolBase value ? value : null;
        if (tool instanceof McpToolkitContributor.McpBridgeTool mcpTool) {
            return mcpTool.accessEffect(context);
        }
        return "";
    }

    private Map<String, Object> normalizeApproval(String agentId, String sessionId, RuntimeContext ctx,
                                                  ToolUseBlock toolCall,
                                                  ToolRiskClassifier.Assessment assessment) {
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
        approval.put("severity", assessment.severity());
        approval.put("risk_code", assessment.code());
        approval.put("findings_count", 1);
        approval.put("findings_summary", assessment.summary());
        approval.put("tool_params", toolCall.getInput());
        approval.put("created_at", System.currentTimeMillis() / 1000.0);
        approval.put("timeout_seconds", 300);
        Object taskId = ctx != null ? ctx.get("task_id") : null;
        Object runId = ctx != null ? ctx.get("run_id") : null;
        if (taskId != null && !String.valueOf(taskId).isBlank()) approval.put("task_id", taskId);
        if (runId != null && !String.valueOf(runId).isBlank()) approval.put("run_id", runId);
        return approval;
    }

    private void recordTaskEvent(RuntimeContext ctx, AgentEvent event) {
        if (taskService == null || ctx == null || event == null) return;
        String taskId = stringValue(ctx.get("task_id"));
        String runId = stringValue(ctx.get("run_id"));
        if (taskId.isBlank() || runId.isBlank()) return;
        EnterpriseTaskService.Delegation sourceDelegation = delegationForSource(taskId, event.getSource());
        String eventTaskId = sourceDelegation == null ? taskId : sourceDelegation.childTaskId();
        String eventRunId = sourceDelegation == null ? runId : sourceDelegation.childRunId();
        try {
            switch (event.getType()) {
                case MODEL_CALL_START -> taskService.appendRuntimeEvent(
                        eventTaskId, eventRunId, "model_request_started", Map.of());
                case MODEL_CALL_END -> {
                    ModelCallEndEvent ended = (ModelCallEndEvent) event;
                    if (ended.getUsage() != null) {
                        taskService.recordUsage(eventTaskId, eventRunId,
                                ended.getUsage().getInputTokens(), ended.getUsage().getOutputTokens());
                    }
                    taskService.appendRuntimeEvent(eventTaskId, eventRunId, "model_request_completed", Map.of());
                }
                case TOOL_CALL_START -> {
                    ToolCallStartEvent started = (ToolCallStartEvent) event;
                    Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("tool_call_id", stringValue(started.getToolCallId()));
                    payload.put("tool_name", FrontendToolCompat.displayToolName(started.getToolCallName()));
                    if ("agent_spawn".equalsIgnoreCase(started.getToolCallName())) {
                        String role = started.getToolCallId() == null ? null
                                : delegationRoles.remove(delegationKey(taskId, started.getToolCallId()));
                        if ("general-purpose".equalsIgnoreCase(role)) role = "researcher";
                        EnterpriseTaskService.Delegation delegation = taskService.startDelegation(taskId, runId, role);
                        activeDelegations.put(delegationKey(taskId, started.getToolCallId()), delegation);
                        payload.put("delegation_id", delegation.delegationId());
                        payload.put("child_task_id", delegation.childTaskId());
                    }
                    taskService.appendRuntimeEvent(eventTaskId, eventRunId, "tool_started", payload);
                }
                case TOOL_RESULT_START -> {
                    ToolResultStartEvent started = (ToolResultStartEvent) event;
                    if (isLifecycleTool(started.getToolCallName())) {
                        toolResultBuffers.put(
                                delegationKey(taskId, started.getToolCallId()), new StringBuilder());
                    }
                }
                case TOOL_RESULT_TEXT_DELTA -> {
                    ToolResultTextDeltaEvent delta = (ToolResultTextDeltaEvent) event;
                    if (isLifecycleTool(delta.getToolCallName()) && delta.getDelta() != null) {
                        StringBuilder buffer = toolResultBuffers.computeIfAbsent(
                                delegationKey(taskId, delta.getToolCallId()), ignored -> new StringBuilder());
                        synchronized (buffer) {
                            if (buffer.length() < 128_000) buffer.append(delta.getDelta());
                        }
                    }
                }
                case TOOL_RESULT_DATA_DELTA -> {
                    ToolResultDataDeltaEvent delta = (ToolResultDataDeltaEvent) event;
                    if (isLifecycleTool(delta.getToolCallName()) && delta.getData() instanceof TextBlock text
                            && text.getText() != null) {
                        StringBuilder buffer = toolResultBuffers.computeIfAbsent(
                                delegationKey(taskId, delta.getToolCallId()), ignored -> new StringBuilder());
                        synchronized (buffer) {
                            if (buffer.length() < 128_000) buffer.append(text.getText());
                        }
                    }
                }
                case TOOL_RESULT_END -> {
                    ToolResultEndEvent ended = (ToolResultEndEvent) event;
                    String lifecycleOutput = takeToolResult(taskId, ended.getToolCallId());
                    if ("agent_spawn".equalsIgnoreCase(ended.getToolCallName())) {
                        EnterpriseTaskService.Delegation delegation = activeDelegations.remove(
                                delegationKey(taskId, ended.getToolCallId()));
                        if (delegation != null) {
                            String backgroundTaskId = outputField(lifecycleOutput, "task_id");
                            boolean accepted = !backgroundTaskId.isBlank()
                                    && !"FAILED".equalsIgnoreCase(String.valueOf(ended.getState()));
                            if (accepted) {
                                EnterpriseTaskService.Delegation existing = backgroundDelegations.putIfAbsent(
                                        backgroundTaskKey(taskId, backgroundTaskId), delegation);
                                if (existing != null) {
                                    taskService.completeDelegation(delegation, "CANCELLED",
                                            "Equivalent subagent task reused " + backgroundTaskId);
                                } else {
                                    taskService.appendRuntimeEvent(
                                            delegation.childTaskId(), delegation.childRunId(),
                                            "subtask_accepted", Map.of("background_task_id", backgroundTaskId));
                                }
                            } else {
                                String state = "FAILED".equalsIgnoreCase(String.valueOf(ended.getState()))
                                        ? "FAILED" : "SUCCEEDED";
                                taskService.completeDelegation(delegation, state,
                                        "SUCCEEDED".equals(state) ? null
                                                : "Subagent returned " + ended.getState());
                            }
                        }
                    }
                    if ("task_output".equalsIgnoreCase(ended.getToolCallName())
                            || "task_wait".equalsIgnoreCase(ended.getToolCallName())
                            || "task_cancel".equalsIgnoreCase(ended.getToolCallName())) {
                        reconcileDelegations(agentForContext(ctx), ctx, false);
                    }
                    taskService.appendRuntimeEvent(eventTaskId, eventRunId, "tool_completed", Map.of(
                            "tool_call_id", stringValue(ended.getToolCallId()),
                            "tool_name", FrontendToolCompat.displayToolName(ended.getToolCallName()),
                            "state", String.valueOf(ended.getState())));
                }
                case SUBAGENT_EXPOSED -> taskService.appendRuntimeEvent(
                        taskId, runId, "subtask_spawned", Map.of());
                default -> {
                    // Token deltas and display-only events stay in the session stream.
                }
            }
        } catch (EnterpriseQuotaService.QuotaExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Failed to record task event: task={}, run={}, event={}",
                    taskId, runId, event.getType(), e);
        }
    }

    private String delegationKey(String taskId, String toolCallId) {
        return stringValue(taskId) + "\u0000" + stringValue(toolCallId);
    }

    private String taskIdFrom(RuntimeContext context) {
        return context == null ? "" : stringValue(context.get("task_id"));
    }

    private void finishExecution(HarnessAgent agent, RuntimeContext context, SignalType signal) {
        String taskId = taskIdFrom(context);
        reconcileDelegations(agent, context, false);
        List<String> cancelled = SubagentTaskCoordinator.cancelRunning(agent, context);
        if (!cancelled.isEmpty()) {
            log.info("Cancelled unfinished subagent tasks at parent completion: task={}, signal={}, children={}",
                    taskId, signal, cancelled);
        }
        reconcileDelegations(agent, context, true);
        clearDelegationTracking(taskId);
    }

    private void reconcileDelegations(HarnessAgent agent, RuntimeContext context, boolean closeRunning) {
        if (agent == null || taskService == null || context == null) return;
        String parentTaskId = taskIdFrom(context);
        if (parentTaskId.isBlank()) return;
        Map<String, SubagentTaskCoordinator.TaskSnapshot> snapshots = new java.util.HashMap<>();
        for (SubagentTaskCoordinator.TaskSnapshot snapshot : SubagentTaskCoordinator.list(agent, context)) {
            snapshots.put(snapshot.taskId(), snapshot);
        }
        String prefix = parentTaskId + "\u0000";
        for (Map.Entry<String, EnterpriseTaskService.Delegation> entry
                : List.copyOf(backgroundDelegations.entrySet())) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String backgroundTaskId = entry.getKey().substring(prefix.length());
            SubagentTaskCoordinator.TaskSnapshot snapshot = snapshots.get(backgroundTaskId);
            if (snapshot == null || (!snapshot.status().isTerminal() && !closeRunning)) continue;
            if (!backgroundDelegations.remove(entry.getKey(), entry.getValue())) continue;
            String state = snapshot == null ? "CANCELLED" : switch (snapshot.status()) {
                case COMPLETED -> "SUCCEEDED";
                case FAILED -> "FAILED";
                case CANCELLED, PENDING, RUNNING -> "CANCELLED";
            };
            String summary = "SUCCEEDED".equals(state) ? null
                    : "Background subagent task " + backgroundTaskId + " ended as "
                            + (snapshot == null ? "unknown"
                            : snapshot.status().name().toLowerCase(Locale.ROOT));
            taskService.completeDelegation(entry.getValue(), state, summary);
        }
    }

    private HarnessAgent agentForContext(RuntimeContext context) {
        String agentId = context == null ? "" : stringValue(context.get("agent_id"));
        return agentId.isBlank() ? null : multiAgentManager.getAgent(agentId);
    }

    private boolean isLifecycleTool(String name) {
        return "agent_spawn".equalsIgnoreCase(name)
                || "task_output".equalsIgnoreCase(name)
                || "task_wait".equalsIgnoreCase(name)
                || "task_cancel".equalsIgnoreCase(name);
    }

    private String takeToolResult(String taskId, String toolCallId) {
        StringBuilder result = toolResultBuffers.remove(delegationKey(taskId, toolCallId));
        if (result == null) return "";
        synchronized (result) {
            return result.toString();
        }
    }

    private String outputField(String output, String field) {
        if (output == null || output.isBlank()) return "";
        String prefix = field.toLowerCase(Locale.ROOT) + ":";
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String backgroundTaskKey(String parentTaskId, String backgroundTaskId) {
        return stringValue(parentTaskId) + "\u0000" + stringValue(backgroundTaskId);
    }

    private void clearDelegationTracking(String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        String prefix = taskId + "\u0000";
        activeDelegations.keySet().removeIf(key -> key.startsWith(prefix));
        backgroundDelegations.keySet().removeIf(key -> key.startsWith(prefix));
        delegationRoles.keySet().removeIf(key -> key.startsWith(prefix));
        toolResultBuffers.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private EnterpriseTaskService.Delegation delegationForSource(String taskId, String source) {
        if (source == null || source.isBlank()) return null;
        String prefix = stringValue(taskId) + "\u0000";
        for (Map.Entry<String, EnterpriseTaskService.Delegation> entry : activeDelegations.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            EnterpriseTaskService.Delegation delegation = entry.getValue();
            if (source.endsWith("/" + delegation.role())
                    || source.contains("/" + delegation.role() + "/")) return delegation;
        }
        return null;
    }

    private Flux<AgentEvent> streamLoopIteration(HarnessAgent agent, LoopMode mode, String objective,
                                                 RuntimeContext ctx, String agentId, String sessionId,
                                                 int iteration, LoopSettings settings,
                                                 String previousEvaluation, List<String> recentOutputs,
                                                 int interventions) {
        int maxIterations = settings.maxIterations();
        String prompt = iteration == 1
                ? initialLoopPrompt(mode, objective, maxIterations)
                : continuationLoopPrompt(mode, objective, iteration, maxIterations, previousEvaluation);
        StringBuilder visibleText = new StringBuilder();
        AtomicReference<AgentEndEvent> endEvent = new AtomicReference<>();

        return streamWithApprovals(agent, List.of(
                        ConversationVisibility.modelOnlyUserMessage(prompt, "loop_control")),
                        ctx, agentId, sessionId)
                .<AgentEvent>handle((event, sink) -> {
                    if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                        visibleText.append(delta.getDelta());
                    }
                    if (event instanceof AgentEndEvent end) {
                        endEvent.set(end);
                    } else {
                        sink.next(event);
                    }
                })
                .concatWith(Flux.defer(() -> {
                    AgentEndEvent terminal = endEvent.get() != null
                            ? endEvent.get() : new AgentEndEvent("loop-" + iteration);
                    String answer = visibleText.toString().trim();
                    List<String> nextOutputs = appendRecentOutput(recentOutputs, answer, settings.doomWindow());
                    DoomDecision doom = settings.detectDoom() ? detectDoom(nextOutputs, settings) : DoomDecision.none();
                    if (iteration >= maxIterations || answer.isBlank() || doom.stop()) {
                        return Flux.<AgentEvent>just(terminal);
                    }
                    if (!settings.rubricEnabled()) {
                        return Flux.<AgentEvent>just(terminal);
                    }
                    String evaluationContext = doom.reminder() == null
                            ? answer
                            : answer + "\n\nRepetition protection reminder:\n" + doom.reminder();
                    return evaluateLoop(agent, mode, objective, evaluationContext, settings.rubricPrompt())
                            .flatMapMany(evaluation -> {
                                if (evaluation.complete()) return Flux.<AgentEvent>just(terminal);
                                if (interventions >= settings.maxInterventions()) {
                                    return Flux.<AgentEvent>just(terminal);
                                }
                                return streamLoopIteration(agent, mode, objective, ctx, agentId,
                                        sessionId, iteration + 1, settings, evaluation.detail(),
                                        nextOutputs, interventions + 1);
                            });
                }));
    }

    private Mono<LoopEvaluation> evaluateLoop(HarnessAgent agent, LoopMode mode,
                                              String objective, String answer, String configuredPrompt) {
        String defaultPrompt = "You are a strict adversarial completion evaluator. Assess whether the latest agent output "
                + "has actually completed the " + mode.name().toLowerCase() + " objective. "
                + "For mission mode, require delegated work to be reconciled into a final result with no unresolved "
                + "internal contradictions, invalid numeric reasoning, unsupported material claims, or failed sources "
                + "misrepresented as verified evidence. "
                + "Reply with exactly COMPLETE or CONTINUE on the first line, followed by one short reason.";
        String rubric = configuredPrompt == null || configuredPrompt.isBlank()
                ? defaultPrompt
                : configuredPrompt.replace("{objective}", objective).replace("{answer}", answer)
                        .replace("{mode}", mode.name().toLowerCase(Locale.ROOT));
        rubric += "\n\nObjective:\n" + objective + "\n\nLatest output:\n" + answer;
        GenerateOptions options = GenerateOptions.builder()
                .temperature(0.0)
                .maxTokens(120)
                .build();
        return agent.getModel().stream(List.of(new UserMessage(rubric)), null, options)
                .reduce(new StringBuilder(), (text, response) -> {
                    if (response.getContent() != null) {
                        for (ContentBlock block : response.getContent()) {
                            if (block instanceof TextBlock tb && tb.getText() != null) {
                                text.append(tb.getText());
                            }
                        }
                    }
                    return text;
                })
                .map(StringBuilder::toString)
                .map(String::trim)
                .map(this::parseLoopEvaluation);
    }

    private LoopEvaluation parseLoopEvaluation(String result) {
        if (result == null || result.isBlank()) {
            return new LoopEvaluation(false, "Evaluator returned no result");
        }
        String firstLine = result.lines().findFirst().orElse("").trim();
        boolean complete = "COMPLETE".equals(firstLine.toUpperCase(java.util.Locale.ROOT));
        return new LoopEvaluation(complete, result);
    }

    private LoopSettings loopSettings(String agentId) {
        int maxIterations = 6;
        boolean iterationEnabled = true;
        boolean rubricEnabled = true;
        String rubricPrompt = "";
        int maxInterventions = 3;
        boolean doomEnabled = false;
        int doomWindow = 6;
        double similarity = 0.9;
        List<DoomStage> stages = List.of(new DoomStage(3, "modify_prompt", "Try a different approach."));
        if (configManager != null && configManager.getConfig().getAgent(agentId) != null) {
            Map<String, Object> frontend = configManager.getConfig().getAgent(agentId).getFrontendRunningConfig();
            Map<?, ?> loop = mapValue(frontend != null ? frontend.get("loop") : null);
            Map<?, ?> iteration = mapValue(loop.get("iteration"));
            iterationEnabled = boolValue(iteration.get("enabled"), true);
            maxIterations = clampInt(iteration.get("max_iterations"), maxIterations, 1, 500);
            Map<?, ?> rubric = mapValue(loop.get("rubric"));
            rubricEnabled = boolValue(rubric.get("enabled"), true);
            rubricPrompt = stringValue(rubric.get("prompt"));
            maxInterventions = clampInt(rubric.get("max_interventions"), maxInterventions, 1, 10);
            Map<?, ?> doom = mapValue(loop.get("doom_loop"));
            doomEnabled = boolValue(doom.get("enabled"), false);
            doomWindow = clampInt(doom.get("window_size"), doomWindow, 2, 20);
            similarity = clampDouble(doom.get("similarity_threshold"), similarity, 0, 1);
            stages = doomStages(doom.get("stages"));
        }
        return new LoopSettings(iterationEnabled ? maxIterations : 1, rubricEnabled,
                rubricPrompt, maxInterventions, doomEnabled, doomWindow, similarity, stages);
    }

    private List<DoomStage> doomStages(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of(new DoomStage(3, "modify_prompt", "Try a different approach."));
        List<DoomStage> result = new ArrayList<>();
        for (Object item : list) {
            Map<?, ?> stage = mapValue(item);
            if (stage.isEmpty()) continue;
            String action = "stop".equalsIgnoreCase(stringValue(stage.get("action"))) ? "stop" : "modify_prompt";
            result.add(new DoomStage(clampInt(stage.get("after"), 3, 1, 500), action,
                    stringValue(stage.get("prompt"))));
            if (result.size() >= 10) break;
        }
        return result.isEmpty() ? List.of(new DoomStage(3, "modify_prompt", "Try a different approach.")) : result;
    }

    private List<String> appendRecentOutput(List<String> previous, String answer, int window) {
        List<String> result = new ArrayList<>(previous == null ? List.of() : previous);
        if (answer != null && !answer.isBlank()) result.add(answer);
        int from = Math.max(0, result.size() - window);
        return new ArrayList<>(result.subList(from, result.size()));
    }

    private DoomDecision detectDoom(List<String> outputs, LoopSettings settings) {
        if (outputs == null || outputs.size() < 2) return DoomDecision.none();
        String latest = outputs.get(outputs.size() - 1);
        int repetitions = 0;
        for (int i = 0; i < outputs.size() - 1; i++) {
            if (textSimilarity(latest, outputs.get(i)) >= settings.similarityThreshold()) repetitions++;
        }
        for (DoomStage stage : settings.stages()) {
            if (repetitions == 0) continue;
            if (repetitions + 1 < stage.after()) continue;
            if ("stop".equals(stage.action())) return new DoomDecision(true, stage.prompt());
            return new DoomDecision(false, stage.prompt().isBlank() ? "Avoid repeating the same output." : stage.prompt());
        }
        return DoomDecision.none();
    }

    private double textSimilarity(String first, String second) {
        if (first == null || second == null) return 0;
        String a = first.trim().toLowerCase(Locale.ROOT);
        String b = second.trim().toLowerCase(Locale.ROOT);
        if (a.equals(b)) return 1;
        if (a.isBlank() || b.isBlank()) return 0;
        java.util.Set<String> left = new java.util.HashSet<>(List.of(a.split("\\W+")));
        java.util.Set<String> right = new java.util.HashSet<>(List.of(b.split("\\W+")));
        left.removeIf(String::isBlank);
        right.removeIf(String::isBlank);
        if (left.isEmpty() || right.isEmpty()) return 0;
        java.util.Set<String> intersection = new java.util.HashSet<>(left);
        intersection.retainAll(right);
        java.util.Set<String> union = new java.util.HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private boolean boolValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private int clampInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number number) parsed = number.intValue();
        else if (value != null) {
            try { parsed = Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private double clampDouble(Object value, double fallback, double min, double max) {
        double parsed = fallback;
        if (value instanceof Number number) parsed = number.doubleValue();
        else if (value != null) {
            try { parsed = Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException ignored) { }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private record DoomStage(int after, String action, String prompt) {}

    private record DoomDecision(boolean stop, String reminder) {
        private static DoomDecision none() { return new DoomDecision(false, null); }
    }

    private record LoopSettings(int maxIterations, boolean rubricEnabled, String rubricPrompt,
                                int maxInterventions, boolean detectDoom, int doomWindow,
                                double similarityThreshold, List<DoomStage> stages) {}

    private String initialLoopPrompt(LoopMode mode, String objective, int maxIterations) {
        if (mode == LoopMode.MISSION) {
            return "MISSION MODE. Complete the mission below using the native subagent tools. "
                    + "Decompose it into independent work, delegate meaningful subtasks with agent_spawn "
                    + "using only researcher, executor, or reviewer roles, "
                    + "collect task IDs and use task_wait to join parallel work, then use a reviewer for material factual conclusions. "
                    + "Reconcile conflicts, numeric claims, and source failures before returning one final result. "
                    + "Do not merely describe a plan. You have at most " + maxIterations + " coordinator rounds.\n\n"
                    + "Mission:\n" + objective;
        }
        return "GOAL MODE. Work autonomously toward the objective below. Use available tools, verify results, "
                + "and do not stop at a plan or partial answer. You have at most " + maxIterations
                + " goal rounds and a completion rubric will check each round.\n\nGoal:\n" + objective;
    }

    private String continuationLoopPrompt(LoopMode mode, String objective, int iteration,
                                          int maxIterations, String evaluation) {
        return mode.name() + " MODE CONTINUATION, round " + iteration + " of " + maxIterations + ". "
                + "The completion rubric says the objective is not complete. Continue from the current session state, "
                + "address the evaluator feedback, perform the remaining work, and verify the result.\n\n"
                + "Objective:\n" + objective + "\n\nEvaluator feedback:\n"
                + (evaluation == null || evaluation.isBlank() ? "Continue and verify remaining work." : evaluation);
    }

    private record LoopEvaluation(boolean complete, String detail) {}

    /** Request metadata must not be able to replace the authenticated execution identity. */
    private boolean isReservedContextKey(String key) {
        return switch (key) {
            case "user_id", "session_id", "agent_id", "root_session_id", "root_agent_id",
                    "working_dir", "project_dir" -> true;
            default -> false;
        };
    }

}
