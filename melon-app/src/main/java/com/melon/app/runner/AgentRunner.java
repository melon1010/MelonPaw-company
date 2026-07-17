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
import com.melon.core.config.ConfigManager;
import com.melon.app.service.ApprovalService;
import com.melon.app.mcp.McpToolkitContributor;
import com.melon.app.task.EnterpriseTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.reactivestreams.Subscription;

import java.util.List;
import java.util.ArrayList;
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
    private final ConfigManager configManager;
    private final ToolRiskClassifier toolRiskClassifier;
    private final EnterpriseTaskService taskService;
    private final ConcurrentMap<String, java.util.Set<Subscription>> activeExecutions = new ConcurrentHashMap<>();

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
                    return streamWithApprovals(agent, msgs, ctx, agentId, sessionId)
                            .doOnNext(event -> recordTaskEvent(ctx, event))
                            .doOnSubscribe(subscription -> {
                                tracked.set(subscription);
                                registerExecution(executionKey, subscription);
                            })
                            .doFinally(signal -> unregisterExecution(executionKey, tracked.get()));
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
                    int maxIterations = loopMaxIterations(agentId);
                    return streamLoopIteration(agent, mode, objective, ctx, agentId,
                                    sessionId, 1, maxIterations, null)
                            .doOnNext(event -> recordTaskEvent(ctx, event))
                            .doOnSubscribe(subscription -> {
                                tracked.set(subscription);
                                registerExecution(executionKey, subscription);
                            })
                            .doFinally(signal -> unregisterExecution(executionKey, tracked.get()));
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
                    return streamWithApprovals(agent, msgs, ctx, agentId, sessionId)
                            .doOnNext(event -> recordTaskEvent(ctx, event))
                            .ofType(AgentResultEvent.class)
                            .map(AgentResultEvent::getResult)
                            .last()
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
                        String approvalMode = approvalService != null
                                ? approvalService.getApprovalMode(agentId) : "RISK_BASED";
                        boolean strict = "STRICT".equalsIgnoreCase(approvalMode);
                        boolean disabled = "OFF".equalsIgnoreCase(approvalMode);
                        List<ToolUseBlock> approvalCalls = new ArrayList<>();
                        List<Map<String, Object>> requests = new ArrayList<>();
                        List<ConfirmResult> immediate = new ArrayList<>();
                        for (ToolUseBlock toolCall : confirm.getToolCalls()) {
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
        try {
            switch (event.getType()) {
                case MODEL_CALL_START -> taskService.appendRuntimeEvent(
                        taskId, runId, "model_request_started", Map.of());
                case MODEL_CALL_END -> {
                    ModelCallEndEvent ended = (ModelCallEndEvent) event;
                    if (ended.getUsage() != null) {
                        taskService.recordUsage(taskId, runId,
                                ended.getUsage().getInputTokens(), ended.getUsage().getOutputTokens());
                    }
                    taskService.appendRuntimeEvent(taskId, runId, "model_request_completed", Map.of());
                }
                case TOOL_CALL_START -> {
                    ToolCallStartEvent started = (ToolCallStartEvent) event;
                    taskService.appendRuntimeEvent(taskId, runId, "tool_started", Map.of(
                            "tool_call_id", stringValue(started.getToolCallId()),
                            "tool_name", FrontendToolCompat.displayToolName(started.getToolCallName())));
                }
                case TOOL_RESULT_END -> {
                    ToolResultEndEvent ended = (ToolResultEndEvent) event;
                    taskService.appendRuntimeEvent(taskId, runId, "tool_completed", Map.of(
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
        } catch (RuntimeException e) {
            log.warn("Failed to record task event: task={}, run={}, event={}",
                    taskId, runId, event.getType(), e);
        }
    }

    private Flux<AgentEvent> streamLoopIteration(HarnessAgent agent, LoopMode mode, String objective,
                                                 RuntimeContext ctx, String agentId, String sessionId,
                                                 int iteration, int maxIterations,
                                                 String previousEvaluation) {
        String prompt = iteration == 1
                ? initialLoopPrompt(mode, objective, maxIterations)
                : continuationLoopPrompt(mode, objective, iteration, maxIterations, previousEvaluation);
        StringBuilder visibleText = new StringBuilder();
        AtomicReference<AgentEndEvent> endEvent = new AtomicReference<>();

        return streamWithApprovals(agent, List.of(new UserMessage("__internal__", prompt)),
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
                    if (iteration >= maxIterations || visibleText.toString().isBlank()) {
                        return Flux.<AgentEvent>just(terminal);
                    }
                    return evaluateLoop(agent, mode, objective, visibleText.toString())
                            .flatMapMany(evaluation -> {
                                if (evaluation.complete()) return Flux.<AgentEvent>just(terminal);
                                return streamLoopIteration(agent, mode, objective, ctx, agentId,
                                        sessionId, iteration + 1, maxIterations, evaluation.detail());
                            });
                }));
    }

    private Mono<LoopEvaluation> evaluateLoop(HarnessAgent agent, LoopMode mode,
                                              String objective, String answer) {
        String rubric = "You are a strict completion evaluator. Assess whether the latest agent output "
                + "has actually completed the " + mode.name().toLowerCase() + " objective. "
                + "For mission mode, require delegated work to be reconciled into a final result. "
                + "Reply with exactly COMPLETE or CONTINUE on the first line, followed by one short reason.\n\n"
                + "Objective:\n" + objective + "\n\nLatest output:\n" + answer;
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

    private int loopMaxIterations(String agentId) {
        int fallback = 6;
        if (configManager == null || configManager.getConfig().getAgent(agentId) == null) return fallback;
        Map<String, Object> frontend = configManager.getConfig().getAgent(agentId).getFrontendRunningConfig();
        if (frontend == null) return fallback;
        Object loopRaw = frontend.get("loop");
        if (!(loopRaw instanceof Map<?, ?> loop)) return fallback;
        Object iterationRaw = loop.get("iteration");
        if (!(iterationRaw instanceof Map<?, ?> iteration)) return fallback;
        Object maxRaw = iteration.get("max_iterations");
        if (!(maxRaw instanceof Number number)) return fallback;
        return Math.max(2, Math.min(12, number.intValue()));
    }

    private String initialLoopPrompt(LoopMode mode, String objective, int maxIterations) {
        if (mode == LoopMode.MISSION) {
            return "MISSION MODE. Complete the mission below using the native subagent tools. "
                    + "Decompose it into independent work, delegate meaningful subtasks with agent_spawn, "
                    + "collect the results, verify them, and return one reconciled final result. "
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
