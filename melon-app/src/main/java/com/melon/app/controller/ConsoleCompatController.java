package com.melon.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import com.melon.app.runner.CommandDispatcher;
import com.melon.app.runner.ConversationVisibility;
import com.melon.app.runner.ExplicitSkillInvocation;
import com.melon.app.runner.MelonPawEnvelopeMapper;
import com.melon.app.service.ApprovalService;
import com.melon.app.service.InboxStore;
import com.melon.app.service.SkillService;
import com.melon.app.task.EnterpriseTaskService;
import com.melon.core.config.ConfigManager;
import com.melon.core.agent.CommandHandler;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.util.SafePathUtil;
import com.melon.core.util.WorkspacePathResolver;
import com.melon.core.util.OutboundNetworkPolicy;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.melon.core.util.ValueUtils.stringValue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static com.melon.app.auth.EnterprisePermissions.AGENTS_MANAGE;
import static com.melon.app.auth.EnterprisePermissions.APPROVALS_DECIDE;

/**
 * melonPaw console-compatible endpoints used by the existing frontend.
 */
@RestController
@RequestMapping("/api/console")
public class ConsoleCompatController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleCompatController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunner agentRunner;
    private final ChatManager chatManager;
    private final ApprovalService approvalService;
    private final ConfigManager configManager;
    private final CommandDispatcher commandDispatcher;
    private final SkillService skillService;
    private final MultiAgentManager multiAgentManager;
    private final InboxStore inboxStore;
    private final EnterpriseAgentAccessService agentAccessService;
    private final EnterpriseTaskService taskService;
    private final Map<String, Flux<ServerSentEvent<String>>> activeConsoleStreams = new ConcurrentHashMap<>();

    public ConsoleCompatController(AgentRunner agentRunner, ChatManager chatManager, ApprovalService approvalService,
                                   ConfigManager configManager, CommandDispatcher commandDispatcher,
                                   SkillService skillService, MultiAgentManager multiAgentManager,
                                   InboxStore inboxStore,
                                   EnterpriseAgentAccessService agentAccessService,
                                   EnterpriseTaskService taskService) {
        this.agentRunner = agentRunner;
        this.chatManager = chatManager;
        this.approvalService = approvalService;
        this.configManager = configManager;
        this.commandDispatcher = commandDispatcher;
        this.skillService = skillService;
        this.multiAgentManager = multiAgentManager;
        this.inboxStore = inboxStore;
        this.agentAccessService = agentAccessService;
        this.taskService = taskService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String requestedAgentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String agentId = agentAccessService.resolveExecution(principal, requestedAgentId);
        String userId = principal.id();
        String sessionId = stringValue(body.get("session_id"), "default");
        String channel = stringValue(body.get("channel"), "console");
        String streamKey = consoleStreamKey(agentId, userId, channel, sessionId);
        if (Boolean.TRUE.equals(body.get("reconnect"))) {
            Flux<ServerSentEvent<String>> active = activeConsoleStreams.get(streamKey);
            return active != null ? active : Flux.empty();
        }
        List<Map<String, Object>> frontendContext = frontendUserMessages(agentId, body.get("input"));
        String text = modelFacingText(frontendContext);
        ChatSpec chat = chatManager.beginTurn(
                agentId, sessionId, userId, channel, initialTitle(agentId, text));
        String chatId = chat.getId();
        String turnId = chatManager.activeTurnId(agentId, chatId);
        String requestId = stringValue(body.get("request_id"), stringValue(body.get("client_request_id"), ""));
        EnterpriseTaskService.TaskRun task;
        try {
            task = taskService.startForAgent(
                    "AGENT", userId, agentId, chat.getName(), sessionId, "console",
                    requestId, chatId, requestId);
        } catch (RuntimeException e) {
            chatManager.endTurn(agentId, chatId, userId, turnId);
            throw e;
        }
        if (!task.created()) {
            chatManager.endTurn(agentId, chatId, userId, turnId);
            MelonPawEnvelopeMapper duplicate = new MelonPawEnvelopeMapper(sessionId, task.taskId(), task.runId());
            return Flux.fromIterable(duplicate.completeWithText(
                    "This request was already accepted.", Map.of("task_id", task.taskId(), "run_id", task.runId())));
        }

        CommandHandler.CommandResult command = commandDispatcher.dispatch(text);
        boolean loopCommand = command != null && command.isSuccess()
                && (command.getAction() == CommandHandler.CommandResult.Action.START_GOAL
                    || command.getAction() == CommandHandler.CommandResult.Action.START_MISSION);
        if (isDirectCommand(command)) {
            return executeCommand(agentId, channel, userId, sessionId, command, principal)
                    .onErrorResume(error -> {
                        taskService.fail(task, error);
                        return Mono.just(CommandOutcome.message(
                                error.getMessage() != null ? error.getMessage() : "Command failed"));
                    })
                    .flatMapMany(outcome -> commandResponse(agentId, channel, userId, sessionId,
                            chatId, turnId, frontendContext, outcome, task));
        }

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("agent_id", agentId);
        env.put("channel", channel);
        env.put("source", "console");
        env.put("session_id", sessionId);
        env.put("root_session_id", stringValue(body.get("root_session_id"), sessionId));
        if (body.get("env_info") instanceof Map<?, ?> envInfo) {
            for (var entry : envInfo.entrySet()) {
                env.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (body.get("request_context") instanceof Map<?, ?> requestContext) {
            for (var entry : requestContext.entrySet()) {
                env.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        env.put("task_id", task.taskId());
        env.put("run_id", task.runId());

        String effectiveText = expandExplicitSkillCommand(agentId, text);
        List<Msg> msgs = List.of(effectiveText.equals(text)
                ? new UserMessage(effectiveText)
                : ConversationVisibility.modelOnlyUserMessage(effectiveText, "skill_expansion"));
        MelonPawEnvelopeMapper envelope = new MelonPawEnvelopeMapper(sessionId, task.taskId(), task.runId());
        AtomicBoolean taskSettled = new AtomicBoolean();
        AtomicReference<Flux<ServerSentEvent<String>>> detachedRef = new AtomicReference<>();
        Flux<io.agentscope.core.event.AgentEvent> agentEvents;
        if (loopCommand) {
            AgentRunner.LoopMode mode = command.getAction() == CommandHandler.CommandResult.Action.START_MISSION
                    ? AgentRunner.LoopMode.MISSION : AgentRunner.LoopMode.GOAL;
            String objective = stringValue(command.getData().get("objective"), "");
            taskService.setRetryIntent(task, new EnterpriseTaskService.RetryIntent(
                    mode == AgentRunner.LoopMode.MISSION ? "LOOP_MISSION" : "LOOP_GOAL", objective, env));
            agentEvents = agentRunner.streamLoop(agentId, mode, objective, userId, sessionId, env);
        } else {
            taskService.setRetryIntent(task, new EnterpriseTaskService.RetryIntent(
                    "STREAM", effectiveText, env, !effectiveText.equals(text)));
            agentEvents = agentRunner.stream(agentId, msgs, userId, sessionId, env);
        }
        Flux<ServerSentEvent<String>> execution = agentEvents
                .doOnSubscribe(s -> log.info("Console chat started: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId))
                .flatMapIterable(envelope::translate)
                .concatWith(Flux.defer(() -> {
                    List<ServerSentEvent<String>> completedEvents = envelope.finish();
                    persistConsoleSession(agentId, channel, userId, sessionId,
                            frontendContext, envelope, false);
                    taskService.succeed(task, envelope.visibleAssistantText());
                    taskSettled.set(true);
                    markConsoleChatIdle(agentId, chatId, userId, sessionId, turnId);
                    return Flux.fromIterable(completedEvents);
                }))
                .startWith(envelope.start())
                .onErrorResume(e -> {
                    log.error("Console chat failed: agent={}, user={}, session={}, chat={}", agentId, userId, sessionId, chatId, e);
                    List<ServerSentEvent<String>> errorEvents = envelope.error(e);
                    persistConsoleSession(agentId, channel, userId, sessionId,
                            frontendContext, envelope, true);
                    if (taskSettled.compareAndSet(false, true)) {
                        taskService.fail(task, e);
                    }
                    markConsoleChatIdle(agentId, chatId, userId, sessionId, turnId);
                    return Flux.fromIterable(errorEvents);
                })
                .doFinally(signal -> {
                    try {
                        if (signal == reactor.core.publisher.SignalType.CANCEL
                                && taskSettled.compareAndSet(false, true)) {
                            persistConsoleSession(agentId, channel, userId, sessionId,
                                    frontendContext, envelope, true);
                            taskService.cancel(task, "execution cancelled");
                            markConsoleChatIdle(agentId, chatId, userId, sessionId, turnId);
                        }
                    } finally {
                        Flux<ServerSentEvent<String>> detached = detachedRef.get();
                        if (detached != null) activeConsoleStreams.remove(streamKey, detached);
                        log.info("Console chat finished: agent={}, session={}, chat={}, task={}, signal={}",
                                agentId, sessionId, chatId, task.taskId(), signal);
                    }
                });

        // Keep AgentScope subscribed after the browser leaves the page. The SSE
        // response is only a view of this execution; disconnecting that view must
        // not cancel the Agent or an in-flight shell process.
        Flux<ServerSentEvent<String>> detached = execution.cache();
        detachedRef.set(detached);
        activeConsoleStreams.put(streamKey, detached);
        detached.subscribe(ignored -> { }, error -> log.warn(
                "Detached console chat stream ended unexpectedly: agent={}, session={}, task={}",
                agentId, sessionId, task.taskId(), error));
        return detached;
    }

    private void persistConsoleSession(
            String agentId, String channel, String userId, String sessionId,
            List<Map<String, Object>> frontendContext, MelonPawEnvelopeMapper envelope,
            boolean recoverEnvelopeOutput) {
        try {
            List<Map<String, Object>> recovery = recoverEnvelopeOutput
                    ? envelope.outputStateMessagesSnapshot() : List.of();
            chatManager.saveSessionShadowFromStateStore(agentId, channel, userId, sessionId,
                    sessionContext(frontendContext, recovery),
                    envelope.turnUsageSnapshot());
        } catch (RuntimeException e) {
            log.warn("Failed to persist console session before completion: agent={}, session={}",
                    agentId, sessionId, e);
        }
    }

    private void markConsoleChatIdle(String agentId, String chatId, String userId,
                                     String sessionId, String turnId) {
        try {
            chatManager.endTurn(agentId, chatId, userId, turnId);
        } catch (RuntimeException e) {
            log.warn("Failed to mark console chat idle: agent={}, session={}, chat={}",
                    agentId, sessionId, chatId, e);
        }
    }

    private String consoleStreamKey(String agentId, String userId, String channel, String sessionId) {
        String scopeUser = agentAccessService.isProjectAgent(agentId) ? "__shared__" : userId;
        return agentId + "\u0000" + scopeUser + "\u0000" + channel + "\u0000" + sessionId;
    }

    @PostMapping("/chat/stop")
    public Mono<ResponseEntity<?>> stopChat(
            @RequestParam(value = "chat_id", required = false) String chatIdParam,
            @RequestParam(value = "session_id", required = false) String sessionIdParam,
            @RequestParam(value = "user_id", required = false) String userIdParam,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : Map.of();
        String effectiveAgentId = agentAccessService.resolveExecution(principal, agentId);
        String chatId = stringValue(payload.get("chat_id"), stringValue(chatIdParam, ""));
        ChatSpec chat = chatManager.get(effectiveAgentId, chatId);
        String userId = principal.id();
        boolean sharedChat = agentAccessService.isProjectAgent(effectiveAgentId);
        if (chat != null && !sharedChat && !userId.equals(chat.getUserId())) {
            return Mono.error(new SecurityException("The chat belongs to another user"));
        }
        String requestedSessionId = stringValue(payload.get("session_id"), stringValue(sessionIdParam, ""));
        if (chat == null && !requestedSessionId.isBlank()) {
            chat = chatManager.list(effectiveAgentId, sharedChat ? null : userId, null).stream()
                    .filter(item -> requestedSessionId.equals(item.getSessionId()))
                    .findFirst()
                    .orElse(null);
        }
        if (chat == null) {
            return Mono.just(ResponseEntity.status(404).body(Map.of("detail", "Owned chat not found")));
        }
        String chatSessionId = stringValue(chat.getSessionId(), "default");
        // A stop request is always scoped to the owned chat. Never let a caller
        // replace the session id after ownership has been established.
        String sessionId = chatSessionId;
        String executionUserId = chatManager.activeUserId(effectiveAgentId, chat.getId(), chat.getUserId());
        String activeTurnId = chatManager.activeTurnId(effectiveAgentId, chat.getId());

        boolean interrupted = false;
        String detail = "";
        try {
            HarnessAgent agent = multiAgentManager.getAgent(effectiveAgentId);
            if (agent != null) {
                agent.getDelegate().interrupt(executionUserId, sessionId);
                interrupted = true;
            } else {
                detail = "agent is not running";
            }
        } catch (Exception e) {
            log.warn("Failed to interrupt chat: agent={}, user={}, session={}, chat={}",
                    effectiveAgentId, userId, sessionId, chatId, e);
            return Mono.error(new IllegalStateException("Failed to interrupt chat", e));
        }

        boolean executionCancelled = agentRunner.cancel(effectiveAgentId, executionUserId, sessionId);

        boolean approvalCancelled = sharedChat
                ? approvalService.cancelPendingApproval(effectiveAgentId, sessionId)
                : approvalService.cancelPendingApproval(effectiveAgentId, sessionId, userId);
        int pendingConfirmationsClosed = agentRunner.abandonPendingConfirmations(
                effectiveAgentId, executionUserId, sessionId);
        boolean planCancelled = approvalService.cancelPendingPlan(effectiveAgentId, sessionId);
        if (sharedChat) {
            taskService.cancelActiveAgentSession(effectiveAgentId, sessionId, "cancelled from shared chat");
        } else {
            taskService.cancelActiveSession(userId, effectiveAgentId, sessionId, "cancelled from chat");
        }
        int shellCancelled = 0;
        if (!chatId.isBlank()) {
            chatManager.endTurn(effectiveAgentId, chatId, executionUserId, activeTurnId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopped", true);
        result.put("interrupted", interrupted);
        result.put("execution_cancelled", executionCancelled);
        result.put("approval_cancelled", approvalCancelled);
        result.put("pending_confirmations_closed", pendingConfirmationsClosed);
        result.put("plan_cancelled", planCancelled);
        result.put("shell_cancelled", shellCancelled);
        result.put("chat_id", chatId);
        result.put("agent_id", effectiveAgentId);
        result.put("session_id", sessionId);
        result.put("user_id", userId);
        result.put("execution_user_id", executionUserId);
        result.put("detail", detail);
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/push-messages")
    public Mono<ResponseEntity<?>> pushMessages(
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestParam(value = "session_id", required = false) String sessionId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        boolean elevated = principal.hasAnyPermission(APPROVALS_DECIDE, AGENTS_MANAGE);
        List<Map<String, Object>> pending = sessionId != null
                ? optionalList(elevated
                    ? approvalService.getPendingApproval(agentId, sessionId)
                    : approvalService.getPendingApproval(agentId, sessionId, principal.id()))
                : (elevated
                    ? approvalService.getPendingApprovals(agentId)
                    : approvalService.getPendingApprovals(agentId, principal.id()));
        return Mono.just(ResponseEntity.ok(Map.of(
                "messages", List.of(),
                "pending_approvals", pending
        )));
    }

    @GetMapping("/inbox/events")
    public Mono<ResponseEntity<?>> inboxEvents(@RequestParam(defaultValue = "50") int limit,
                                               @RequestParam(defaultValue = "0") int offset,
                                               @RequestParam(value = "source_type", required = false) String sourceType,
                                               @RequestParam(required = false) String status,
                                               @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                               @RequestParam(value = "unread_only", defaultValue = "false") boolean unreadOnly) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "events",
                inboxStore.listEvents(Math.min(Math.max(limit, 1), 500), Math.max(offset, 0),
                        sourceType, status, agentId, unreadOnly)
        )));
    }

    @PostMapping("/inbox/read")
    public Mono<ResponseEntity<?>> markInboxRead(
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        int updated = body != null && Boolean.TRUE.equals(body.get("all"))
                ? inboxStore.markAllRead(agentId)
                : inboxStore.markRead(stringList(body != null ? body.get("event_ids") : null), agentId);
        return Mono.just(ResponseEntity.ok(Map.of("updated", updated)));
    }

    @DeleteMapping("/inbox/events/{eventId}")
    public Mono<ResponseEntity<?>> deleteInboxEvent(
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @PathVariable String eventId) {
        Map<String, Object> result = inboxStore.deleteEvent(eventId, agentId);
        if (!Boolean.TRUE.equals(result.get("deleted"))) {
            return Mono.just(ResponseEntity.status(404).body(Map.of("detail", "event not found")));
        }
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/inbox/traces/{runId}")
    public Mono<ResponseEntity<?>> inboxTrace(
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @PathVariable String runId) {
        Map<String, Object> trace = inboxStore.getTrace(runId, agentId);
        if ("not_found".equals(trace.get("status"))) {
            return Mono.just(ResponseEntity.status(404).body(Map.of("detail", "trace not found")));
        }
        return Mono.just(ResponseEntity.ok(trace));
    }

    @GetMapping("/debug/backend-logs")
    public Mono<ResponseEntity<?>> backendLogs(@RequestParam(defaultValue = "200") int lines) {
        return Mono.fromCallable(() -> ResponseEntity.ok(backendLogsPayload(lines)));
    }

    private Map<String, Object> backendLogsPayload(int lines) {
        int limit = Math.min(Math.max(lines, 0), 5000);
        Path path = backendLogPath();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", path.toString());
        payload.put("lines", limit);
        payload.put("exists", Files.isRegularFile(path));
        payload.put("updated_at", null);
        payload.put("size", 0L);
        payload.put("content", "");
        if (!Files.isRegularFile(path)) {
            return payload;
        }
        try {
            payload.put("updated_at", Files.getLastModifiedTime(path).toMillis() / 1000);
            payload.put("size", Files.size(path));
            payload.put("content", tailLog(path, limit));
        } catch (Exception e) {
            payload.put("content", "(Error reading log: " + e.getMessage() + ")");
        }
        return payload;
    }

    private Path backendLogPath() {
        String configured = firstNonBlank(
                System.getProperty("logging.file.name"),
                System.getenv("MELON_LOG_FILE"),
                System.getenv("LOG_FILE")
        );
        Path path = configured != null ? Path.of(configured) : Path.of("logs", "melon.log");
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private String tailLog(Path path, int lines) throws Exception {
        if (lines == 0) {
            return "";
        }
        long size = Files.size(path);
        if (size == 0) {
            return "";
        }
        int maxBytes = 512 * 1024;
        long offset = Math.max(0, size - maxBytes);
        byte[] buffer = new byte[(int) (size - offset)];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(offset);
            file.readFully(buffer);
        }
        String content = new String(buffer, StandardCharsets.UTF_8);
        if (offset > 0) {
            int firstNewline = content.indexOf('\n');
            content = firstNewline >= 0 ? content.substring(firstNewline + 1) : "";
        }
        String[] all = content.split("\\R", -1);
        int end = all.length;
        if (end > 0 && all[end - 1].isEmpty()) {
            end--;
        }
        int start = Math.max(0, end - lines);
        List<String> selected = new ArrayList<>();
        for (int i = start; i < end; i++) {
            selected.add(all[i]);
        }
        return String.join("\n", selected);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadCompat(
            @RequestPart("file") FilePart file,
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        String original = file.filename() != null ? file.filename() : "upload.bin";
        String stored = UUID.randomUUID().toString().replace("-", "") + "_" + safeFileName(original);
        Path mediaDir = SafePathUtil.resolveSafe(configManager.resolveWorkspaceDir(agentId), "media");
        Path target = SafePathUtil.resolveSafe(mediaDir, stored);
        return Mono.fromCallable(() -> {
                    Files.createDirectories(mediaDir);
                    return target;
                })
                .flatMap(path -> file.transferTo(path).thenReturn(path))
                .map(path -> ResponseEntity.ok(Map.of(
                        "url", path.toAbsolutePath().normalize().toString(),
                        "file_name", original,
                        "stored_name", stored,
                        "size", path.toFile().length()
                )));
    }

    private String titleFromText(String text) {
        if (text == null || text.isBlank()) return "New Chat";
        String title = text.strip().replaceAll("\\s+", " ");
        return title.length() > 30 ? title.substring(0, 30) : title;
    }

    private String initialTitle(String agentId, String text) {
        if (!autoTitleEnabled(agentId)) {
            return "New Chat";
        }
        return titleFromText(text);
    }

    private boolean autoTitleEnabled(String agentId) {
        try {
            var config = configManager.getConfig().getAgent(agentId);
            Object raw = config != null && config.getFrontendRunningConfig() != null
                    ? config.getFrontendRunningConfig().get("auto_title_config")
                    : null;
            if (raw instanceof Map<?, ?> map && map.get("enabled") != null) {
                return Boolean.parseBoolean(String.valueOf(map.get("enabled")));
            }
        } catch (Exception ignored) {
            // Use default below.
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private String extractInputText(Object input) {
        if (input == null) return "";
        if (input instanceof String s) return s;
        if (input instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object content = map.get("content");
                    parts.add(extractContentText(content));
                } else {
                    parts.add(String.valueOf(item));
                }
            }
            return String.join("\n", parts).trim();
        }
        if (input instanceof Map<?, ?> map) {
            return extractContentText(map.get("content"));
        }
        return String.valueOf(input);
    }

    private String extractContentText(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> blocks) {
            List<String> parts = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && "text".equals(String.valueOf(map.get("type")))) {
                    Object text = map.get("text");
                    if (text != null) parts.add(String.valueOf(text));
                }
            }
            return String.join("\n", parts);
        }
        return String.valueOf(content);
    }

    private List<Map<String, Object>> optionalList(Map<String, Object> value) {
        return value != null ? List.of(value) : List.of();
    }

    private Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private List<Map<String, Object>> frontendUserMessages(String agentId, Object input) {
        if (!(input instanceof List<?> list)) {
            return List.of(frontendUserMessage(normalizeContentBlocks(agentId,
                    List.of(Map.of("type", "text", "text", extractInputText(input))))));
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            if (!"user".equalsIgnoreCase(String.valueOf(map.get("role")))) continue;
            messages.add(frontendUserMessage(normalizeContentBlocks(agentId, map.get("content"))));
        }
        return messages;
    }

    private Map<String, Object> frontendUserMessage(List<Map<String, Object>> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "user_" + UUID.randomUUID().toString().replace("-", ""));
        message.put("role", "user");
        message.put("content", content);
        message.put("metadata", Map.of());
        message.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
        return message;
    }

    private List<Map<String, Object>> normalizeContentBlocks(String agentId, Object content) {
        if (content instanceof String text) return List.of(Map.of("type", "text", "text", text));
        if (!(content instanceof List<?> blocks)) return List.of(Map.of("type", "text", "text", String.valueOf(content == null ? "" : content)));
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> raw)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            raw.forEach((key, value) -> item.put(String.valueOf(key), value));
            normalizeMediaSource(agentId, item);
            normalized.add(item);
            String path = uploadedAbsolutePath(item);
            if (!path.isBlank() && !hasUploadNotice(normalized, path)) {
                normalized.add(Map.of("type", "text", "text", "用户上传文件，已经下载到 " + path));
            }
        }
        return normalized.isEmpty() ? List.of(Map.of("type", "text", "text", "")) : normalized;
    }

    private void normalizeMediaSource(String agentId, Map<String, Object> item) {
        String type = String.valueOf(item.getOrDefault("type", "text"));
        Object url = switch (type) {
            case "image" -> item.get("image_url");
            case "video" -> item.get("video_url");
            case "audio" -> item.get("data");
            case "file" -> item.get("file_url");
            case "data" -> item.get("url");
            default -> null;
        };
        if (item.get("source") instanceof Map<?, ?> sourceRaw) {
            Map<String, Object> source = new LinkedHashMap<>();
            sourceRaw.forEach((key, value) -> source.put(String.valueOf(key), value));
            Object sourceUrl = source.get("url");
            if (sourceUrl != null) {
                source.put("url", toFileUrl(agentId, String.valueOf(sourceUrl)));
            }
            source.putIfAbsent("media_type", mediaType(type));
            if (isMediaType(type)) {
                item.put("type", "data");
                item.putIfAbsent("name", item.getOrDefault("file_name", item.getOrDefault("filename", "file")));
            }
            item.put("source", source);
            return;
        }
        if (url == null) return;
        String fileUrl = toFileUrl(agentId, String.valueOf(url));
        item.put("type", "data");
        item.put("source", Map.of("type", "url", "url", fileUrl, "media_type", mediaType(type)));
        item.putIfAbsent("name", item.getOrDefault("file_name", item.getOrDefault("filename", "file")));
        if ("file".equals(type) && !item.containsKey("filename")) {
            item.put("filename", String.valueOf(item.getOrDefault("file_name", "file")));
        }
    }

    private String modelFacingText(List<Map<String, Object>> messages) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (!(content instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> raw && "text".equals(String.valueOf(raw.get("type")))) {
                    String text = stringValue(raw.get("text"), "");
                    if (!text.isBlank()) parts.add(text);
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private boolean isDirectCommand(CommandHandler.CommandResult result) {
        if (result == null || result.getType() == CommandHandler.CommandType.UNKNOWN) return false;
        return result.getAction() != CommandHandler.CommandResult.Action.START_GOAL
                && result.getAction() != CommandHandler.CommandResult.Action.START_MISSION;
    }

    private Mono<CommandOutcome> executeCommand(String agentId, String channel, String userId,
                                                String sessionId, CommandHandler.CommandResult result,
                                                EnterprisePrincipal principal) {
        if (!result.isSuccess()) return Mono.just(CommandOutcome.message(result.getMessage()));
        return switch (result.getAction()) {
            case SWITCH_MODEL -> Mono.fromCallable(() -> {
                if (!principal.hasPermission("agents:manage")) {
                    return CommandOutcome.message("Model selection is managed by your organization.");
                }
                return CommandOutcome.message(switchModel(
                        agentId, stringValue(result.getData().get("model"), "")));
            });
            case LIST_SKILLS -> Mono.fromCallable(() -> CommandOutcome.message(listSkills(agentId)));
            case ADD_SKILL -> Mono.fromCallable(() -> CommandOutcome.message(
                    addSkill(agentId, stringValue(result.getData().get("skill"), ""))));
            case REMOVE_SKILL -> Mono.fromCallable(() -> CommandOutcome.message(
                    removeSkill(agentId, stringValue(result.getData().get("skill"), ""))));
            case CLEAR_HISTORY -> Mono.fromCallable(() -> {
                agentRunner.cancel(agentId, userId, sessionId);
                boolean shared = agentAccessService.isProjectAgent(agentId);
                if (shared) {
                    approvalService.cancelPendingApproval(agentId, sessionId);
                    for (String stateUserId : chatManager.stateUsers(
                            agentId, channel, sessionId, userId)) {
                        multiAgentManager.clearSession(agentId, stateUserId, sessionId);
                    }
                } else {
                    approvalService.cancelPendingApproval(agentId, sessionId, userId);
                    multiAgentManager.clearSession(agentId, userId, sessionId);
                }
                String storageUserId = chatManager.storageUserId(
                        agentId, channel, sessionId, userId);
                chatManager.clearSession(agentId, channel, storageUserId, sessionId);
                chatManager.resetSharedStateMetadata(agentId, channel, sessionId);
                return new CommandOutcome("Conversation context cleared.",
                        Map.of("clear_history", true), false);
            });
            case COMPACT_CONTEXT -> multiAgentManager.compactSession(
                            agentId, userId, sessionId,
                            stringValue(result.getData().get("instruction"), ""))
                    .map(compacted -> CommandOutcome.message(compacted
                            ? "Conversation context compacted and persisted."
                            : "There is not enough conversation context to compact."));
            case NEW_SESSION -> Mono.fromCallable(() -> {
                String name = stringValue(result.getData().get("session_name"), "New chat");
                ChatSpec created = chatManager.create(agentId, name, userId, channel, null);
                return new CommandOutcome("Created a new chat: " + created.getName(),
                        Map.of("new_session_id", created.getSessionId(), "new_chat_id", created.getId()), true);
            });
            case SHOW_HELP -> Mono.just(CommandOutcome.message(result.getMessage()));
            default -> Mono.just(CommandOutcome.message(result.getMessage()));
        };
    }

    private Flux<ServerSentEvent<String>> commandResponse(
            String agentId, String channel, String userId, String sessionId, String chatId, String turnId,
            List<Map<String, Object>> frontendContext, CommandOutcome outcome,
            EnterpriseTaskService.TaskRun task) {
        MelonPawEnvelopeMapper envelope = new MelonPawEnvelopeMapper(sessionId, task.taskId(), task.runId());
        if (outcome.persistShadow()) {
            saveCommandShadow(agentId, channel, userId, sessionId, frontendContext, outcome.text());
        }
        chatManager.endTurn(agentId, chatId, userId, turnId);
        taskService.succeed(task, outcome.text());
        return Flux.fromIterable(envelope.completeWithText(outcome.text(), outcome.metadata()))
                .doFinally(signal -> log.info(
                        "Console command handled: agent={}, session={}, chat={}, signal={}",
                        agentId, sessionId, chatId, signal));
    }

    private String switchModel(String agentId, String model) {
        if (model == null || model.isBlank()) return "Usage: /model <provider:model>";
        var agent = configManager.getConfig().getAgent(agentId);
        if (agent == null) return "Agent not found: " + agentId;
        agent.setActiveModel(model);
        configManager.save();
        multiAgentManager.reload(agentId);
        return "Active model switched to " + model;
    }

    private String listSkills(String agentId) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> skill : skillService.listSkills(agentId)) {
            String enabled = Boolean.TRUE.equals(skill.get("enabled")) ? "enabled" : "disabled";
            String description = stringValue(skill.get("description"), "");
            String name = stringValue(skill.get("name"), "");
            String invocation = "enabled".equals(enabled) ? "/" + name : name;
            lines.add("- " + invocation + " [" + enabled + "]"
                    + (description.isBlank() ? "" : ": " + description));
        }
        return lines.isEmpty() ? "No workspace skills are installed." : String.join("\n", lines);
    }

    private String addSkill(String agentId, String name) throws java.io.IOException {
        boolean installed = skillService.listSkills(agentId).stream()
                .anyMatch(skill -> name.equals(stringValue(skill.get("name"), "")));
        if (installed) {
            skillService.enableSkill(agentId, name);
        } else {
            boolean inPool = skillService.listPoolSkills().stream()
                    .anyMatch(skill -> name.equals(stringValue(skill.get("name"), "")));
            if (!inPool) throw new IllegalArgumentException("Skill not found in workspace or shared pool: " + name);
            skillService.downloadPoolSkillToWorkspace(agentId, name, false);
            skillService.enableSkill(agentId, name);
        }
        multiAgentManager.reload(agentId);
        return "Skill enabled: " + name;
    }

    private String removeSkill(String agentId, String name) throws java.io.IOException {
        skillService.disableSkill(agentId, name);
        multiAgentManager.reload(agentId);
        return "Skill disabled: " + name;
    }

    private String expandExplicitSkillCommand(String agentId, String text) {
        if (text == null || !text.trim().startsWith("/")) return text;
        String[] parts = text.trim().split("\\s+", 2);
        String requested = parts[0].substring(1);
        Map<String, Object> skill = skillService.listSkills(agentId).stream()
                .filter(item -> Boolean.TRUE.equals(item.get("enabled")))
                .filter(item -> requested.equalsIgnoreCase(stringValue(item.get("name"), "")))
                .findFirst()
                .orElse(null);
        if (skill == null) return text;
        String request = parts.length > 1 && !parts[1].isBlank()
                ? parts[1].trim() : "Apply this skill and report the result.";
        try {
            String instructions = skillService.getSkillContent(agentId, requested);
            return ExplicitSkillInvocation.modelPrompt(requested, instructions, request);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load skill '" + requested + "'", e);
        }
    }

    private record CommandOutcome(String text, Map<String, Object> metadata, boolean persistShadow) {
        private static CommandOutcome message(String text) {
            return new CommandOutcome(text == null ? "" : text, Map.of(), true);
        }
    }

    private void saveCommandShadow(String agentId, String channel, String userId, String sessionId,
                                   List<Map<String, Object>> frontendContext, String reply) {
        List<Map<String, Object>> context = new ArrayList<>(frontendContext);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("id", "assistant_" + UUID.randomUUID().toString().replace("-", ""));
        assistant.put("role", "assistant");
        assistant.put("name", agentId);
        assistant.put("content", List.of(Map.of("type", "text", "text", reply != null ? reply : "")));
        assistant.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
        context.add(assistant);
        chatManager.saveSessionShadowFromStateStore(agentId, channel, userId, sessionId, context);
    }

    private List<Map<String, Object>> sessionContext(List<Map<String, Object>> frontendContext,
                                                     List<Map<String, Object>> outputContext) {
        List<Map<String, Object>> context = new ArrayList<>(frontendContext != null ? frontendContext : List.of());
        if (outputContext != null) context.addAll(outputContext);
        return context;
    }

    private String uploadedAbsolutePath(Map<String, Object> item) {
        Object source = item.get("source");
        if (!(source instanceof Map<?, ?> raw)) return "";
        Object url = raw.get("url");
        if (url == null) return "";
        String text = String.valueOf(url);
        if (!text.startsWith("file://")) return "";
        try {
            return Path.of(URI.create(text)).toString();
        } catch (Exception ignored) {
            return text.substring("file://".length());
        }
    }

    private boolean hasUploadNotice(List<Map<String, Object>> blocks, String path) {
        String notice = "用户上传文件，已经下载到 " + path;
        return blocks.stream().anyMatch(block ->
                "text".equals(String.valueOf(block.get("type"))) && notice.equals(String.valueOf(block.get("text"))));
    }

    private String toFileUrl(String agentId, String value) {
        if (value == null || value.isBlank() || value.startsWith("data:")) {
            return value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return OutboundNetworkPolicy.requirePublicHttpUri(value).toString();
        }
        Path workspace = configManager.resolveWorkspaceDir(agentId);
        Path path;
        if (value.startsWith("file://")) {
            path = Path.of(URI.create(value));
        } else {
            path = Path.of(value);
        }
        if (!path.isAbsolute()) {
            path = SafePathUtil.resolveSafe(SafePathUtil.resolveSafe(workspace, "media"), value);
        } else {
            path = new WorkspacePathResolver(workspace.toString()).resolve(path.toString());
        }
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Uploaded file is missing or is not a regular workspace file");
        }
        return path.toAbsolutePath().normalize().toUri().toString();
    }

    private String mediaType(String type) {
        return switch (type) {
            case "image" -> "image/*";
            case "video" -> "video/*";
            case "audio" -> "audio/*";
            default -> "application/octet-stream";
        };
    }

    private boolean isMediaType(String type) {
        return "file".equals(type) || "image".equals(type) || "audio".equals(type) || "video".equals(type);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) result.add(String.valueOf(item));
        }
        return result;
    }

    private String errorJson(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "{\"detail\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return errorJson(e);
        }
    }

    private String safeFileName(String name) {
        String cleaned = name.replace("..", "").replace("/", "").replace("\\", "").replace("\0", "");
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return cleaned.isBlank() ? "upload.bin" : cleaned;
    }
}
