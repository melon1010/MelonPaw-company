package com.melon.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Application-level policy around AgentScope's durable subagent task repository.
 *
 * <p>AgentScope remains responsible for executing and persisting tasks. This coordinator adds the
 * Web application semantics that the generic Harness cannot infer: subagent calls are asynchronous
 * by default, equivalent in-flight requests are idempotent, multiple tasks can be waited on with a
 * bounded timeout, and explicit session cancellation reaches the actual task futures.
 */
public final class SubagentTaskCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SubagentTaskCoordinator.class);
    private static final String SPAWN_TOOL = "agent_spawn";
    private static final String WAIT_TOOL = "task_wait";
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private SubagentTaskCoordinator() {}

    /** Installs the application policy around the Harness tools after the agent is built. */
    public static void install(HarnessAgent agent, Path workspaceDir) {
        if (agent == null || workspaceDir == null) return;
        TaskRepository repository = repository(agent);
        if (repository == null) {
            log.warn("Subagent task repository is unavailable for agent {}", agent.getAgentId());
            return;
        }
        Toolkit toolkit = agent.getToolkit();
        AgentTool delegate = toolkit.getTool(SPAWN_TOOL);
        if (delegate != null && !(delegate instanceof ManagedAgentSpawnTool)) {
            InvocationLedger ledger = new InvocationLedger(
                    workspaceDir.resolve(".melonpaw").resolve("subagent-invocations.json"));
            toolkit.removeTool(SPAWN_TOOL);
            toolkit.registerAgentTool(new ManagedAgentSpawnTool(delegate, repository, ledger));
        }
        if (toolkit.getTool(WAIT_TOOL) == null) {
            toolkit.registerAgentTool(new TaskWaitTool(repository));
        }
    }

    /** Returns the repository already owned by the Harness subagent middleware. */
    public static TaskRepository repository(HarnessAgent agent) {
        if (agent == null) return null;
        for (var middleware : agent.getDelegate().getMiddlewares()) {
            if (middleware instanceof SubagentsMiddleware subagents) {
                return subagents.getTaskRepository();
            }
            if (middleware instanceof DynamicSubagentsMiddleware subagents) {
                return subagents.getTaskRepository();
            }
        }
        return null;
    }

    /** Snapshot all task states for one authenticated session. */
    public static List<TaskSnapshot> list(HarnessAgent agent, RuntimeContext context) {
        TaskRepository repository = repository(agent);
        if (repository == null) return List.of();
        String sessionId = context != null ? context.getSessionId() : null;
        Collection<BackgroundTask> tasks = repository.listTasks(context, sessionId, null);
        List<TaskSnapshot> result = new ArrayList<>(tasks.size());
        for (BackgroundTask task : tasks) {
            result.add(new TaskSnapshot(task.getTaskId(), task.getAgentId(), task.getTaskStatus()));
        }
        return List.copyOf(result);
    }

    /** Cancels every non-terminal task in the session and returns the affected task IDs. */
    public static List<String> cancelRunning(HarnessAgent agent, RuntimeContext context) {
        TaskRepository repository = repository(agent);
        if (repository == null) return List.of();
        String sessionId = context != null ? context.getSessionId() : null;
        Set<String> cancelled = new LinkedHashSet<>();
        for (BackgroundTask task : repository.listTasks(context, sessionId, null)) {
            if (!task.getTaskStatus().isTerminal()
                    && repository.cancelTask(context, sessionId, task.getTaskId())) {
                cancelled.add(task.getTaskId());
            }
        }
        return List.copyOf(cancelled);
    }

    public record TaskSnapshot(String taskId, String agentId, TaskStatus status) {}

    static final class ManagedAgentSpawnTool extends ToolBase {
        private static final int MAX_SOURCE_TASKS = 16;
        private static final int MAX_HANDOFF_CHARS = 64_000;
        private final AgentTool delegate;
        private final TaskRepository repository;
        private final InvocationLedger ledger;
        private final ConcurrentMap<String, Mono<ToolResultBlock>> starting = new ConcurrentHashMap<>();

        ManagedAgentSpawnTool(
                AgentTool delegate, TaskRepository repository, InvocationLedger ledger) {
            super(ToolBase.builder()
                    .name(SPAWN_TOOL)
                    .description(delegate.getDescription()
                            + " The call always starts in the background and returns a stable task_id immediately. "
                            + "Equivalent unfinished work in the same session reuses its existing task_id. "
                            + "For reviewer tasks, source_task_ids injects completed worker results directly.")
                    .inputSchema(withSourceTaskIds(delegate.getParameters()))
                    .readOnly(false)
                    .concurrencySafe(true)
                    .stateInjected(delegate instanceof ToolBase tool && tool.isStateInjected()));
            this.delegate = delegate;
            this.repository = repository;
            this.ledger = ledger;
        }

        @Override
        public Boolean getStrict() {
            return delegate.getStrict();
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return delegate.getOutputSchema();
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return delegate instanceof ToolBase tool
                    ? tool.checkPermissions(toolInput, context)
                    : super.checkPermissions(toolInput, context);
        }

        @Override
        public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
            return delegate instanceof ToolBase tool
                    ? tool.matchRule(ruleContent, toolInput)
                    : super.matchRule(ruleContent, toolInput);
        }

        @Override
        public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
            return delegate instanceof ToolBase tool
                    ? tool.generateSuggestions(toolInput)
                    : super.generateSuggestions(toolInput);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            RuntimeContext context = param != null ? param.getRuntimeContext() : null;
            Map<String, Object> input = new LinkedHashMap<>(param != null ? param.getInput() : Map.of());
            if ((input.get("agent_id") == null || String.valueOf(input.get("agent_id")).isBlank())
                    && input.get("role") != null && !String.valueOf(input.get("role")).isBlank()) {
                input.put("agent_id", input.get("role"));
            }
            List<String> sourceTaskIds = taskIds(input.remove("source_task_ids"));
            if ("reviewer".equalsIgnoreCase(String.valueOf(input.get("agent_id")))
                    && !sourceTaskIds.isEmpty()) {
                String handoff = sourceTaskHandoff(context, sourceTaskIds);
                if (handoff.startsWith("Error:")) {
                    return Mono.just(ToolResultBlock.text(handoff));
                }
                input.put("task", String.valueOf(input.getOrDefault("task", "")) + "\n\n" + handoff);
            }
            input.put("timeout_seconds", 0);
            String fingerprint = invocationFingerprint(context, input);
            if (input.get("label") == null || String.valueOf(input.get("label")).isBlank()) {
                input.put("label", "job-" + fingerprint.substring(0, 12));
            }

            LedgerEntry reusable = ledger.reusable(fingerprint, repository, context);
            if (reusable != null) {
                return Mono.just(ToolResultBlock.text(reusable.response()
                        + "\nnote: Equivalent work is already tracked; the existing task_id was reused."));
            }

            ToolCallParam delegated = ToolCallParam.builder(param).input(input).build();
            Mono<ToolResultBlock> created = delegate.callAsync(delegated)
                    .doOnNext(result -> {
                        String response = resultText(result);
                        String taskId = field(response, "task_id");
                        if (!taskId.isBlank()) {
                            ledger.record(fingerprint, taskId, response);
                        }
                    })
                    .doFinally(ignored -> starting.remove(fingerprint))
                    .cache();
            Mono<ToolResultBlock> raced = starting.putIfAbsent(fingerprint, created);
            return raced != null ? raced : created;
        }

        private String sourceTaskHandoff(RuntimeContext context, List<String> taskIds) {
            String sessionId = context != null ? context.getSessionId() : null;
            StringBuilder result = new StringBuilder(
                    "Authoritative worker results follow. Review these first; do not scan chat or session history.");
            for (String taskId : taskIds) {
                BackgroundTask task = repository.getTask(context, sessionId, taskId);
                if (task == null) return "Error: source task not found: " + taskId;
                if (!task.getTaskStatus().isTerminal()) return "Error: source task is still running: " + taskId;
                result.append("\n\n--- source task ").append(taskId)
                        .append(" ---\nstatus: ").append(task.getTaskStatus().name().toLowerCase(Locale.ROOT));
                String output = task.getResult();
                if (output != null && !output.isBlank()) {
                    result.append("\nresult:\n").append(output);
                } else if (task.getError() != null) {
                    result.append("\nerror: ").append(task.getError().getMessage());
                }
            }
            return limit(result.toString(), MAX_HANDOFF_CHARS);
        }

        private static List<String> taskIds(Object value) {
            if (!(value instanceof Collection<?> raw)) return List.of();
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Object item : raw) {
                String id = item == null ? "" : String.valueOf(item).trim();
                if (!id.isBlank()) result.add(id);
                if (result.size() >= MAX_SOURCE_TASKS) break;
            }
            return List.copyOf(result);
        }

        private static Map<String, Object> withSourceTaskIds(Map<String, Object> schema) {
            Map<String, Object> result = new LinkedHashMap<>(schema != null ? schema : Map.of());
            Map<String, Object> properties = new LinkedHashMap<>();
            if (result.get("properties") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> properties.put(String.valueOf(key), value));
            }
            properties.put("source_task_ids", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "maxItems", MAX_SOURCE_TASKS,
                    "description", "Completed worker task IDs whose full results the reviewer must inspect"));
            result.put("properties", properties);
            return result;
        }
    }

    static final class TaskWaitTool extends ToolBase {
        private static final int MAX_TASKS = 32;
        private static final long MAX_TIMEOUT_MS = 600_000;
        private static final int MAX_RESULT_CHARS = 24_000;
        private static final int MAX_OUTPUT_CHARS = 96_000;
        private final TaskRepository repository;

        TaskWaitTool(TaskRepository repository) {
            super(ToolBase.builder()
                    .name(WAIT_TOOL)
                    .description("Wait for several background subagent tasks with one bounded call. "
                            + "A timeout returns the latest states and never means the tasks failed.")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "task_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                                            "maxItems", MAX_TASKS),
                                    "mode", Map.of("type", "string", "enum", List.of("wait_any", "wait_all"),
                                            "default", "wait_all"),
                                    "timeout_ms", Map.of("type", "integer", "minimum", 0,
                                            "maximum", MAX_TIMEOUT_MS, "default", 30_000)),
                            "required", List.of("task_ids")))
                    .readOnly(true)
                    .concurrencySafe(true));
            this.repository = repository;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> ToolResultBlock.text(waitForTasks(param)))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private String waitForTasks(ToolCallParam param) throws InterruptedException {
            RuntimeContext context = param != null ? param.getRuntimeContext() : null;
            Map<String, Object> input = param != null ? param.getInput() : Map.of();
            List<String> taskIds = taskIds(input.get("task_ids"));
            if (taskIds.isEmpty()) return "Error: task_ids must contain at least one task ID";
            String mode = String.valueOf(input.getOrDefault("mode", "wait_all")).toLowerCase(Locale.ROOT);
            boolean waitAny = "wait_any".equals(mode);
            long timeoutMs = longValue(input.get("timeout_ms"), 30_000, 0, MAX_TIMEOUT_MS);
            long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
            List<BackgroundTask> tasks;
            do {
                tasks = resolve(context, taskIds);
                long terminal = tasks.stream().filter(task -> task.getTaskStatus().isTerminal()).count();
                int missing = taskIds.size() - tasks.size();
                if ((waitAny && (terminal > 0 || missing > 0))
                        || (!waitAny && terminal + missing == taskIds.size())) break;
                if (timeoutMs == 0 || System.nanoTime() >= deadline) break;
                Thread.sleep(Math.min(100, Math.max(1,
                        Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
            } while (true);

            String sessionId = context != null ? context.getSessionId() : null;
            StringBuilder output = new StringBuilder("mode: ").append(waitAny ? "wait_any" : "wait_all");
            Set<String> resolvedIds = new LinkedHashSet<>();
            for (BackgroundTask task : tasks) {
                resolvedIds.add(task.getTaskId());
                TaskStatus status = task.getTaskStatus();
                output.append("\n\ntask_id: ").append(task.getTaskId())
                        .append("\nstatus: ").append(status.name().toLowerCase(Locale.ROOT));
                if (status.isTerminal()) {
                    repository.markDelivered(context, sessionId, task.getTaskId());
                    String result = task.getResult();
                    if (result != null && !result.isBlank()) {
                        output.append("\nresult:\n").append(limit(result, MAX_RESULT_CHARS));
                    } else if (task.getError() != null) {
                        output.append("\nerror: ").append(task.getError().getMessage());
                    }
                }
            }
            for (String taskId : taskIds) {
                if (!resolvedIds.contains(taskId)) {
                    output.append("\n\ntask_id: ").append(taskId).append("\nstatus: not_found");
                }
            }
            boolean unfinished = tasks.stream().anyMatch(task -> !task.getTaskStatus().isTerminal());
            if (unfinished) {
                output.append("\n\nnote: Wait timeout reached; unfinished tasks are still running.");
            }
            return limit(output.toString(), MAX_OUTPUT_CHARS);
        }

        private List<BackgroundTask> resolve(RuntimeContext context, List<String> taskIds) {
            String sessionId = context != null ? context.getSessionId() : null;
            List<BackgroundTask> tasks = new ArrayList<>();
            for (String taskId : taskIds) {
                BackgroundTask task = repository.getTask(context, sessionId, taskId);
                if (task != null) tasks.add(task);
            }
            return tasks;
        }

        private List<String> taskIds(Object value) {
            if (!(value instanceof Collection<?> raw)) return List.of();
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Object item : raw) {
                String id = item == null ? "" : String.valueOf(item).trim();
                if (!id.isBlank()) result.add(id);
                if (result.size() >= MAX_TASKS) break;
            }
            return List.copyOf(result);
        }
    }

    static final class InvocationLedger {
        private static final TypeReference<LinkedHashMap<String, LedgerEntry>> TYPE = new TypeReference<>() {};
        private final Path file;
        private final Map<String, LedgerEntry> entries;

        InvocationLedger(Path file) {
            this.file = file.toAbsolutePath().normalize();
            Map<String, LedgerEntry> loaded = JsonUtils.load(this.file, TYPE);
            this.entries = loaded != null ? new LinkedHashMap<>(loaded) : new LinkedHashMap<>();
            pruneExpired();
        }

        private synchronized LedgerEntry reusable(
                String fingerprint, TaskRepository repository, RuntimeContext context) {
            LedgerEntry entry = entries.get(fingerprint);
            if (entry == null) return null;
            if (createdAt(entry).plus(IDEMPOTENCY_RETENTION).isBefore(Instant.now())) {
                remove(fingerprint);
                return null;
            }
            String sessionId = context != null ? context.getSessionId() : null;
            BackgroundTask task = repository.getTask(context, sessionId, entry.taskId());
            if (task == null || task.getTaskStatus() == TaskStatus.CANCELLED
                    || (task.getTaskStatus().isTerminal()
                    && repository.isDelivered(context, sessionId, entry.taskId()))) {
                remove(fingerprint);
                return null;
            }
            return entry;
        }

        private synchronized void record(String fingerprint, String taskId, String response) {
            entries.put(fingerprint, new LedgerEntry(taskId, response, Instant.now().toString()));
            save();
        }

        private void remove(String fingerprint) {
            entries.remove(fingerprint);
            save();
        }

        private void pruneExpired() {
            Instant cutoff = Instant.now().minus(IDEMPOTENCY_RETENTION);
            if (entries.entrySet().removeIf(entry -> createdAt(entry.getValue()).isBefore(cutoff))) {
                save();
            }
        }

        private Instant createdAt(LedgerEntry entry) {
            try {
                return Instant.parse(entry.createdAt());
            } catch (RuntimeException ignored) {
                return Instant.EPOCH;
            }
        }

        private void save() {
            JsonUtils.save(file, entries);
        }
    }

    record LedgerEntry(String taskId, String response, String createdAt) {}

    private static String invocationFingerprint(RuntimeContext context, Map<String, Object> input) {
        String userId = context != null && context.getUserId() != null ? context.getUserId() : "";
        String sessionId = context != null && context.getSessionId() != null ? context.getSessionId() : "";
        String agentId = String.valueOf(input.getOrDefault("agent_id", "")).trim();
        String task = String.valueOf(input.getOrDefault("task", "")).trim().replaceAll("\\s+", " ");
        return sha256(userId + "\u0000" + sessionId + "\u0000" + agentId + "\u0000" + task);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String resultText(ToolResultBlock result) {
        if (result == null) return "";
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock value && value.getText() != null) {
                if (!text.isEmpty()) text.append('\n');
                text.append(value.getText());
            }
        }
        return text.toString();
    }

    private static String field(String text, String name) {
        if (text == null || text.isBlank()) return "";
        String prefix = name + ":";
        for (String line : text.split("\\R")) {
            if (line.trim().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return line.trim().substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static long longValue(Object value, long fallback, long min, long max) {
        long parsed = fallback;
        if (value instanceof Number number) parsed = number.longValue();
        else if (value != null) {
            try {
                parsed = Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "\n... output truncated ...";
    }
}
