package com.melon.app.task;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ConversationVisibility;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.MelonPawEnvelopeMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/** Replays persisted task input through the existing AgentRunner. */
@Component
public class EnterpriseTaskRetryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseTaskRetryCoordinator.class);

    private final EnterpriseTaskService tasks;
    private final AgentRunner agentRunner;
    private final ChatManager chatManager;

    public EnterpriseTaskRetryCoordinator(EnterpriseTaskService tasks, AgentRunner agentRunner,
                                          ChatManager chatManager) {
        this.tasks = tasks;
        this.agentRunner = agentRunner;
        this.chatManager = chatManager;
    }

    public EnterpriseTaskService.TaskRun retry(String taskId, String actorUserId) {
        EnterpriseTaskService.RetryStart retry = tasks.beginRetry(taskId, actorUserId);
        Map<String, Object> task = tasks.get(taskId);
        EnterpriseTaskService.TaskRun run = retry.run();
        String agentId = text(task.get("agent_id"), "default");
        String ownerUserId = text(task.get("owner_user_id"), "default");
        String sessionId = text(task.get("session_id"), "default");
        String chatId = text(task.get("chat_id"), "");
        String channel = text(retry.intent().env().get("channel"), "console");
        String executionUserId = chatManager.isSharedAgent(agentId) ? actorUserId : ownerUserId;
        launch(retry.intent(), run, agentId, executionUserId, sessionId, chatId, channel);
        return run;
    }

    /** Creates and starts a task that is independent from the interactive chat registry. */
    public EnterpriseTaskService.TaskRun submit(String ownerUserId, String agentId, String title,
                                                String prompt, String mode, String idempotencyKey) {
        String executionMode = switch (text(mode, "agent").toLowerCase(Locale.ROOT)) {
            case "agent" -> "STREAM";
            case "goal" -> "LOOP_GOAL";
            case "mission" -> "LOOP_MISSION";
            default -> throw new IllegalArgumentException("mode must be agent, goal, or mission");
        };
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (prompt.length() > 100_000) {
            throw new IllegalArgumentException("prompt is too large");
        }
        String sessionId = "task-" + UUID.randomUUID();
        String requestKey = text(idempotencyKey, UUID.randomUUID().toString());
        String taskTitle = text(title, prompt.strip().replaceAll("\\s+", " "));
        if (taskTitle.length() > 200) taskTitle = taskTitle.substring(0, 200);
        EnterpriseTaskService.TaskRun run = tasks.startForAgent(
                "AGENT", ownerUserId, agentId, taskTitle, sessionId,
                "manual", requestKey, null, requestKey);
        if (!run.created()) {
            return run;
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("agent_id", agentId);
        env.put("channel", "task-center");
        env.put("source", "task-center");
        env.put("session_id", sessionId);
        EnterpriseTaskService.RetryIntent intent =
                new EnterpriseTaskService.RetryIntent(executionMode, prompt, env);
        tasks.setRetryIntent(run, intent);
        launch(intent, run, agentId, ownerUserId, sessionId, "", "task-center");
        return run;
    }

    private void launch(EnterpriseTaskService.RetryIntent intent,
                        EnterpriseTaskService.TaskRun run,
                        String agentId, String executionUserId, String sessionId,
                        String chatId, String channel) {
        String turnId = "";
        try {
            if (!chatId.isBlank()) {
                chatManager.beginExistingTurn(agentId, chatId, executionUserId);
                turnId = chatManager.activeTurnId(agentId, chatId);
            }
            String activeTurnId = turnId;
            execute(intent, run, agentId, executionUserId, sessionId)
                    .doOnSuccess(summary -> tasks.succeed(run, summary))
                    .doOnError(error -> tasks.fail(run, error))
                    .doFinally(signal -> finishChat(
                            agentId, chatId, channel, executionUserId, sessionId, activeTurnId))
                    .onErrorResume(error -> {
                        log.warn("Task retry failed: task={}, run={}", run.taskId(), run.runId(), error);
                        return Mono.empty();
                    })
                    .subscribe();
        } catch (RuntimeException error) {
            tasks.fail(run, error);
            finishChat(agentId, chatId, channel, executionUserId, sessionId, turnId);
            log.warn("Task retry could not start: task={}, run={}", run.taskId(), run.runId(), error);
        }
    }

    private Mono<String> execute(EnterpriseTaskService.RetryIntent intent,
                                 EnterpriseTaskService.TaskRun run,
                                 String agentId, String ownerUserId, String sessionId) {
        Map<String, Object> env = new LinkedHashMap<>(intent.env());
        env.put("task_id", run.taskId());
        env.put("run_id", run.runId());
        env.put("retry_of_task_id", run.taskId());
        return switch (intent.mode()) {
            case "QUERY" -> agentRunner.query(agentId, List.of(userMessage(intent)),
                            ownerUserId, sessionId, env)
                    .map(reply -> reply != null && reply.getTextContent() != null ? reply.getTextContent() : "");
            case "LOOP_GOAL" -> collect(agentRunner.streamLoop(agentId, AgentRunner.LoopMode.GOAL,
                    intent.message(), ownerUserId, sessionId, env), sessionId, run);
            case "LOOP_MISSION" -> collect(agentRunner.streamLoop(agentId, AgentRunner.LoopMode.MISSION,
                    intent.message(), ownerUserId, sessionId, env), sessionId, run);
            default -> collect(agentRunner.stream(agentId, List.of(userMessage(intent)),
                    ownerUserId, sessionId, env), sessionId, run);
        };
    }

    private UserMessage userMessage(EnterpriseTaskService.RetryIntent intent) {
        return intent.internal()
                ? ConversationVisibility.modelOnlyUserMessage(intent.message(), "task_retry")
                : new UserMessage(intent.message());
    }

    private Mono<String> collect(Flux<AgentEvent> events, String sessionId,
                                 EnterpriseTaskService.TaskRun run) {
        MelonPawEnvelopeMapper envelope = new MelonPawEnvelopeMapper(sessionId, run.taskId(), run.runId());
        return events.doOnNext(envelope::translate)
                .then(Mono.fromSupplier(() -> {
                    envelope.finish();
                    return envelope.visibleAssistantText();
                }));
    }

    private void finishChat(String agentId, String chatId, String channel,
                            String executionUserId, String sessionId, String turnId) {
        try {
            chatManager.saveSessionShadowFromStateStore(
                    agentId, channel, executionUserId, sessionId);
        } catch (RuntimeException e) {
            log.warn("Failed to refresh chat after task retry: agent={}, session={}", agentId, sessionId, e);
        } finally {
            if (!chatId.isBlank()) {
                chatManager.endTurn(agentId, chatId, executionUserId, turnId);
            }
        }
    }

    private String text(Object value, String fallback) {
        String result = value == null ? "" : String.valueOf(value);
        return result.isBlank() ? fallback : result;
    }
}
