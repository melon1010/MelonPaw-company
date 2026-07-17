package com.melon.app.task;

import com.melon.app.auth.EnterpriseAgentDefinitionService;
import com.melon.app.auth.EnterpriseIdentityStore;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent task, run and lifecycle-event backbone for every Agent execution entry point. */
@Service
public class EnterpriseTaskService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseTaskService.class);
    private static final int MAX_RESULT_CHARS = 32_000;
    private static final int MAX_EVENT_JSON_CHARS = 16_000;
    private static final int MAX_RETRY_INTENT_JSON_CHARS = 512_000;
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "REJECTED", "EXPIRED", "INTERRUPTED");
    private static final Set<String> RETRYABLE = Set.of("FAILED", "TIMED_OUT", "INTERRUPTED");
    private static final Set<String> RETRY_MODES = Set.of("STREAM", "QUERY", "LOOP_GOAL", "LOOP_MISSION");

    private final String jdbcUrl;
    private final EnterpriseIdentityStore identityStore;
    private final EnterpriseAgentDefinitionService agentDefinitions;
    private final Set<String> mutatingAgents = new HashSet<>();

    @Autowired
    public EnterpriseTaskService(
            @Value("${melon.enterprise.database-path:}") String configuredPath,
            @Value("${melon.home_dir:~/.melonAI}") String homeDir,
            EnterpriseIdentityStore identityStore,
            EnterpriseAgentDefinitionService agentDefinitions) {
        this(resolveDatabasePath(configuredPath, homeDir), identityStore, agentDefinitions);
    }

    public EnterpriseTaskService(Path databasePath) {
        this(databasePath, null, null);
    }

    private EnterpriseTaskService(Path databasePath,
                                  EnterpriseIdentityStore identityStore,
                                  EnterpriseAgentDefinitionService agentDefinitions) {
        try {
            Path parent = databasePath.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create enterprise task database directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        this.identityStore = identityStore;
        this.agentDefinitions = agentDefinitions;
        initialize();
        recoverInterruptedRuns();
    }

    public synchronized TaskRun startPersonal(
            String taskType,
            String ownerUserId,
            String agentId,
            String title,
            String sessionId,
            String triggerType,
            String triggerRef,
            String chatId,
            String idempotencyKey) {
        String owner = required(ownerUserId, "owner user");
        String versionId = agentDefinitions != null ? agentDefinitions.currentVersionId(agentId) : null;
        return startScoped(taskType, owner, agentId, title, sessionId, triggerType, triggerRef,
                chatId, idempotencyKey, "PERSONAL", owner, versionId);
    }

    public synchronized TaskRun startForAgent(
            String taskType,
            String ownerUserId,
            String agentId,
            String title,
            String sessionId,
            String triggerType,
            String triggerRef,
            String chatId,
            String idempotencyKey) {
        String owner = required(ownerUserId, "owner user");
        String agent = required(agentId, "Agent");
        String scopeType = "SERVICE";
        String scopeId = agent;
        if (identityStore != null) {
            EnterpriseIdentityStore.AgentBindingRecord binding = identityStore.findAgentBinding(agent).orElse(null);
            if (binding != null && "PROJECT".equals(binding.scopeType())) {
                if (!identityStore.canUseProjectAgent(agent, owner)) {
                    throw new SecurityException("The execution user cannot use this project Agent");
                }
                scopeType = "PROJECT";
                scopeId = binding.projectId();
            } else if (binding != null && "PERSONAL".equals(binding.scopeType())) {
                if (!owner.equals(binding.ownerUserId())) {
                    throw new SecurityException("The execution user does not own this personal Agent");
                }
                scopeType = "PERSONAL";
                scopeId = owner;
            }
        }
        String versionId = agentDefinitions != null ? agentDefinitions.currentVersionId(agent) : null;
        return startScoped(taskType, owner, agent, title, sessionId, triggerType, triggerRef,
                chatId, idempotencyKey, scopeType, scopeId, versionId);
    }

    private TaskRun startScoped(
            String taskType,
            String ownerUserId,
            String agentId,
            String title,
            String sessionId,
            String triggerType,
            String triggerRef,
            String chatId,
            String idempotencyKey,
            String scopeType,
            String scopeId,
            String agentVersionId) {
        String owner = required(ownerUserId, "owner user");
        String agent = required(agentId, "Agent");
        String session = required(sessionId, "session");
        String scope = normalized(scopeType, "PERSONAL");
        String scopeOwner = required(scopeId, "scope id");
        String key = blankToNull(idempotencyKey);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (key != null) {
                    TaskRun existing = findByIdempotency(connection, owner, key);
                    if (existing != null) {
                        connection.rollback();
                        return existing;
                    }
                }
                if (mutatingAgents.contains(agent)) {
                    throw new AgentMutationInProgressException(
                            "Agent resources are being updated; retry the task shortly");
                }
                if (hasActiveRun(connection, owner, agent, session)) {
                    throw new IllegalStateException("This session already has an active task");
                }

                String now = Instant.now().toString();
                String taskId = UUID.randomUUID().toString();
                String runId = UUID.randomUUID().toString();
                String type = normalized(taskType, "AGENT");
                String trigger = normalized(triggerType, "console").toLowerCase(Locale.ROOT);
                String initiatorType = "AUTOMATION".equals(type) ? "SYSTEM"
                        : ("CHANNEL".equals(type) ? "CHANNEL" : "USER");

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO enterprise_tasks (
                            task_id, task_type, scope_type, scope_id, owner_user_id,
                            initiator_type, initiator_id, agent_id, agent_version_id, title, status,
                            session_id, trigger_type, trigger_ref, chat_id, idempotency_key,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    int i = 1;
                    statement.setString(i++, taskId);
                    statement.setString(i++, type);
                    statement.setString(i++, scope);
                    statement.setString(i++, scopeOwner);
                    statement.setString(i++, owner);
                    statement.setString(i++, initiatorType);
                    statement.setString(i++, "SYSTEM".equals(initiatorType) ? value(triggerRef, trigger) : owner);
                    statement.setString(i++, agent);
                    statement.setString(i++, blankToNull(agentVersionId));
                    statement.setString(i++, limited(value(title, "Untitled task"), 240));
                    statement.setString(i++, session);
                    statement.setString(i++, trigger);
                    statement.setString(i++, blankToNull(triggerRef));
                    statement.setString(i++, blankToNull(chatId));
                    statement.setString(i++, key);
                    statement.setString(i++, now);
                    statement.setString(i, now);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO enterprise_task_runs (
                            run_id, task_id, attempt, state, phase, agent_version_id, started_at
                        ) VALUES (?, ?, 1, 'RUNNING', 'AGENT', ?, ?)
                        """)) {
                    statement.setString(1, runId);
                    statement.setString(2, taskId);
                    statement.setString(3, blankToNull(agentVersionId));
                    statement.setString(4, now);
                    statement.executeUpdate();
                }
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("task_type", type);
                created.put("trigger_type", trigger);
                created.put("scope_type", scope);
                created.put("scope_id", scopeOwner);
                if (!blank(agentVersionId)) created.put("agent_version_id", agentVersionId);
                appendEvent(connection, taskId, runId, "task_created", "USER", owner, created);
                appendEvent(connection, taskId, runId, "run_started", "SYSTEM", "melonpaw",
                        Map.of("attempt", 1));
                connection.commit();
                return new TaskRun(taskId, runId, true);
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("start task", e);
        }
    }

    public synchronized void appendRuntimeEvent(
            String taskId, String runId, String eventType, Map<String, Object> payload) {
        if (blank(taskId) || blank(runId)) return;
        try (Connection connection = open()) {
            appendEvent(connection, taskId, runId, eventType, "AGENT", "agent", payload);
        } catch (SQLException e) {
            log.warn("Failed to append task event: task={}, run={}, type={}", taskId, runId, eventType, e);
        }
    }

    /** Stores the normalized execution input needed to create a real retry later. */
    public synchronized void setRetryIntent(TaskRun task, RetryIntent intent) {
        if (task == null || !task.created() || intent == null) return;
        try {
            String json = JsonUtils.toJson(intent);
            if (json.length() > MAX_RETRY_INTENT_JSON_CHARS) {
                log.warn("Task {} input is too large to support retry", task.taskId());
                return;
            }
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE enterprise_tasks SET retry_intent_json = ? WHERE task_id = ?")) {
                statement.setString(1, json);
                statement.setString(2, task.taskId());
                if (statement.executeUpdate() != 1) {
                    log.warn("Task {} disappeared before saving retry input", task.taskId());
                }
            }
        } catch (RuntimeException | SQLException e) {
            // Retry metadata must never make the original execution fail.
            log.warn("Failed to save retry input for task={}", task.taskId(), e);
        }
    }

    /** Creates a new attempt under the existing task while retaining every previous run and event. */
    public synchronized RetryStart beginRetry(String taskId, String actorUserId) {
        String id = required(taskId, "task id");
        String actor = required(actorUserId, "actor user");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String status;
                String owner;
                String agent;
                String session;
                String scopeType;
                String storedVersionId;
                String retryJson;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT status, owner_user_id, agent_id, session_id, scope_type,
                               agent_version_id, retry_intent_json
                        FROM enterprise_tasks WHERE task_id = ?
                        """)) {
                    statement.setString(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("Task not found");
                        status = result.getString("status");
                        owner = result.getString("owner_user_id");
                        agent = result.getString("agent_id");
                        session = result.getString("session_id");
                        scopeType = result.getString("scope_type");
                        storedVersionId = result.getString("agent_version_id");
                        retryJson = result.getString("retry_intent_json");
                    }
                }
                if (!RETRYABLE.contains(status)) {
                    throw new IllegalStateException("Only failed, timed out, or interrupted tasks can be retried");
                }
                RetryIntent intent = parseRetryIntent(retryJson);
                if (intent == null) throw new IllegalStateException("This task does not contain a replayable input");
                if (identityStore != null && "PROJECT".equals(scopeType)
                        && !identityStore.canUseProjectAgent(agent, owner)) {
                    throw new SecurityException("The task owner can no longer use this project Agent");
                }
                if (mutatingAgents.contains(agent)) {
                    throw new AgentMutationInProgressException(
                            "Agent resources are being updated; retry the task shortly");
                }
                if (hasActiveRun(connection, owner, agent, session)) {
                    throw new IllegalStateException("This session already has an active task");
                }

                int attempt;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COALESCE(MAX(attempt), 0) + 1 FROM enterprise_task_runs WHERE task_id = ?")) {
                    statement.setString(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        attempt = result.next() ? result.getInt(1) : 1;
                    }
                }
                String now = Instant.now().toString();
                String runId = UUID.randomUUID().toString();
                String versionId = agentDefinitions != null
                        ? value(agentDefinitions.currentVersionId(agent), storedVersionId)
                        : storedVersionId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO enterprise_task_runs (
                            run_id, task_id, attempt, state, phase, agent_version_id, started_at)
                        VALUES (?, ?, ?, 'RUNNING', 'AGENT', ?, ?)
                        """)) {
                    statement.setString(1, runId);
                    statement.setString(2, id);
                    statement.setInt(3, attempt);
                    statement.setString(4, blankToNull(versionId));
                    statement.setString(5, now);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE enterprise_tasks
                        SET status = 'RUNNING', agent_version_id = ?, result_summary = NULL,
                            error_code = NULL, updated_at = ?
                        WHERE task_id = ?
                        """)) {
                    statement.setString(1, blankToNull(versionId));
                    statement.setString(2, now);
                    statement.setString(3, id);
                    statement.executeUpdate();
                }
                Map<String, Object> retryEvent = new LinkedHashMap<>();
                retryEvent.put("attempt", attempt);
                retryEvent.put("previous_status", status);
                if (!blank(versionId)) retryEvent.put("agent_version_id", versionId);
                appendEvent(connection, id, runId, "retry_requested", "USER", actor, retryEvent);
                appendEvent(connection, id, runId, "run_started", "SYSTEM", "melonpaw",
                        Map.of("attempt", attempt));
                connection.commit();
                return new RetryStart(new TaskRun(id, runId, true), intent);
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("retry task", e);
        }
    }

    public synchronized void recordUsage(String taskId, String runId, long inputTokens, long outputTokens) {
        if (blank(taskId) || blank(runId)) return;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE enterprise_task_runs
                    SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?
                    WHERE run_id = ? AND task_id = ?
                    """)) {
                statement.setLong(1, Math.max(0, inputTokens));
                statement.setLong(2, Math.max(0, outputTokens));
                statement.setString(3, runId);
                statement.setString(4, taskId);
                statement.executeUpdate();
                appendEvent(connection, taskId, runId, "usage_recorded", "AGENT", "agent",
                        Map.of("input_tokens", Math.max(0, inputTokens),
                                "output_tokens", Math.max(0, outputTokens)));
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            log.warn("Failed to record task usage: task={}, run={}", taskId, runId, e);
        }
    }

    public void waitingApproval(String taskId, String runId, Map<String, Object> approval) {
        transition(taskId, runId, "WAITING_APPROVAL", "approval_created", approval, null, null, false);
    }

    public void approvalResolved(String taskId, String runId, Map<String, Object> decision, boolean resume) {
        transition(taskId, runId, resume ? "RUNNING" : "WAITING_APPROVAL",
                "approval_resolved", decision, null, null, false);
    }

    public void succeed(TaskRun task, String resultSummary) {
        if (task != null) succeed(task.taskId(), task.runId(), resultSummary);
    }

    public void succeed(String taskId, String runId, String resultSummary) {
        transition(taskId, runId, "SUCCEEDED", "run_succeeded", Map.of(), resultSummary, null, true);
    }

    public void fail(TaskRun task, Throwable error) {
        if (task != null) fail(task.taskId(), task.runId(), error);
    }

    public void fail(String taskId, String runId, Throwable error) {
        String message = error == null ? "Task failed" : value(error.getMessage(), error.getClass().getSimpleName());
        transition(taskId, runId, "FAILED", "run_failed", Map.of("error", limited(message, 1000)),
                null, limited(message, 1000), true);
    }

    public void cancel(TaskRun task, String reason) {
        if (task != null) cancel(task.taskId(), task.runId(), reason);
    }

    public void cancel(String taskId, String runId, String reason) {
        transition(taskId, runId, "CANCELLED", "run_cancelled",
                Map.of("reason", limited(value(reason, "cancelled"), 500)), null, null, true);
    }

    public synchronized Map<String, Object> cancelActiveSession(
            String ownerUserId, String agentId, String sessionId, String reason) {
        Map<String, Object> active = findActive(ownerUserId, agentId, sessionId);
        if (active.isEmpty()) return Map.of();
        cancel(String.valueOf(active.get("task_id")), String.valueOf(active.get("run_id")), reason);
        return active;
    }

    public synchronized boolean hasActiveAgent(String agentId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM enterprise_tasks
                WHERE agent_id = ? AND status IN ('RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED')
                LIMIT 1
                """)) {
            statement.setString(1, required(agentId, "Agent"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw databaseFailure("check active Agent tasks", e);
        }
    }

    /** Acquires the short-lived write lease used by project resource HTTP mutations. */
    public synchronized boolean beginAgentMutation(String agentId) {
        String agent = required(agentId, "Agent");
        return !hasActiveAgent(agent) && mutatingAgents.add(agent);
    }

    public synchronized void endAgentMutation(String agentId) {
        if (!blank(agentId)) mutatingAgents.remove(agentId.trim());
    }

    public synchronized List<Map<String, Object>> list(
            String ownerUserId, boolean elevated, String status, String source, String query, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, r.run_id, r.attempt, r.started_at, r.ended_at,
                       r.input_tokens, r.output_tokens, r.safe_error
                FROM enterprise_tasks t
                JOIN enterprise_task_runs r ON r.run_id = (
                    SELECT r2.run_id FROM enterprise_task_runs r2
                    WHERE r2.task_id = t.task_id ORDER BY r2.attempt DESC LIMIT 1
                )
                WHERE 1 = 1
                """);
        if (!elevated) {
            if (identityStore != null) {
                sql.append(" AND (t.owner_user_id = ? OR (t.scope_type = 'PROJECT' AND EXISTS ("
                        + "SELECT 1 FROM enterprise_project_members pm "
                        + "WHERE pm.project_id = t.scope_id AND pm.user_id = ?)))");
                params.add(required(ownerUserId, "owner user"));
                params.add(required(ownerUserId, "owner user"));
            } else {
                sql.append(" AND t.owner_user_id = ?");
                params.add(required(ownerUserId, "owner user"));
            }
        }
        if (!blank(status)) {
            sql.append(" AND t.status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (!blank(source)) {
            sql.append(" AND t.trigger_type = ?");
            params.add(source.trim().toLowerCase(Locale.ROOT));
        }
        if (!blank(query)) {
            sql.append(" AND (LOWER(t.title) LIKE ? OR LOWER(t.task_id) LIKE ?)");
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY t.updated_at DESC LIMIT ? OFFSET ?");
        params.add(Math.max(1, Math.min(limit, 200)));
        params.add(Math.max(0, offset));
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) rows.add(taskRow(result));
                return rows;
            }
        } catch (SQLException e) {
            throw databaseFailure("list tasks", e);
        }
    }

    public synchronized Map<String, Object> get(String taskId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT t.*, r.run_id, r.attempt, r.started_at, r.ended_at,
                       r.input_tokens, r.output_tokens, r.safe_error
                FROM enterprise_tasks t
                JOIN enterprise_task_runs r ON r.run_id = (
                    SELECT r2.run_id FROM enterprise_task_runs r2
                    WHERE r2.task_id = t.task_id ORDER BY r2.attempt DESC LIMIT 1
                )
                WHERE t.task_id = ?
                """)) {
            statement.setString(1, required(taskId, "task id"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? taskRow(result) : Map.of();
            }
        } catch (SQLException e) {
            throw databaseFailure("get task", e);
        }
    }

    public synchronized List<Map<String, Object>> runs(String taskId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM enterprise_task_runs WHERE task_id = ? ORDER BY attempt DESC")) {
            statement.setString(1, required(taskId, "task id"));
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) rows.add(runRow(result));
                return rows;
            }
        } catch (SQLException e) {
            throw databaseFailure("list task runs", e);
        }
    }

    public synchronized List<Map<String, Object>> events(String taskId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM enterprise_task_events WHERE task_id = ? ORDER BY occurred_at, sequence")) {
            statement.setString(1, required(taskId, "task id"));
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) rows.add(eventRow(result));
                return rows;
            }
        } catch (SQLException e) {
            throw databaseFailure("list task events", e);
        }
    }

    public boolean canRead(Map<String, Object> task, String userId, boolean elevated) {
        if (elevated) return true;
        String user = required(userId, "user id");
        if (task == null || task.isEmpty()) return false;
        if (user.equals(task.get("owner_user_id"))) return true;
        return identityStore != null
                && "PROJECT".equals(task.get("scope_type"))
                && identityStore.isProjectMember(String.valueOf(task.get("scope_id")), user);
    }

    public boolean canManage(Map<String, Object> task, String userId, boolean elevated) {
        if (elevated) return true;
        String user = required(userId, "user id");
        if (task == null || task.isEmpty()) return false;
        if (user.equals(task.get("owner_user_id"))) return true;
        if (identityStore == null || !"PROJECT".equals(task.get("scope_type"))) return false;
        String role = identityStore.projectRole(String.valueOf(task.get("scope_id")), user);
        return "OWNER".equals(role) || "MANAGER".equals(role);
    }

    public boolean isTerminal(Map<String, Object> task) {
        return task != null && TERMINAL.contains(String.valueOf(task.get("status")));
    }

    private synchronized void transition(
            String taskId, String runId, String state, String eventType, Map<String, Object> payload,
            String resultSummary, String error, boolean terminal) {
        if (blank(taskId) || blank(runId)) return;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!isCurrentNonTerminalRun(connection, taskId, runId)) {
                    connection.rollback();
                    return;
                }
                String now = Instant.now().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE enterprise_tasks
                        SET status = ?, result_summary = COALESCE(?, result_summary),
                            error_code = ?, updated_at = ?
                        WHERE task_id = ?
                        """)) {
                    statement.setString(1, state);
                    statement.setString(2, resultSummary == null ? null : limited(resultSummary, MAX_RESULT_CHARS));
                    statement.setString(3, error == null ? null : "TASK_FAILED");
                    statement.setString(4, now);
                    statement.setString(5, taskId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE enterprise_task_runs
                        SET state = ?, phase = ?, ended_at = ?, error_code = ?, safe_error = ?
                        WHERE run_id = ? AND task_id = ?
                        """)) {
                    statement.setString(1, state);
                    statement.setString(2, state);
                    statement.setString(3, terminal ? now : null);
                    statement.setString(4, error == null ? null : "TASK_FAILED");
                    statement.setString(5, error == null ? null : limited(error, 1000));
                    statement.setString(6, runId);
                    statement.setString(7, taskId);
                    statement.executeUpdate();
                }
                appendEvent(connection, taskId, runId, eventType, "SYSTEM", "melonpaw", payload);
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("transition task", e);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_tasks (
                        task_id TEXT PRIMARY KEY,
                        task_type TEXT NOT NULL,
                        scope_type TEXT NOT NULL,
                        scope_id TEXT NOT NULL,
                        owner_user_id TEXT NOT NULL,
                        initiator_type TEXT NOT NULL,
                        initiator_id TEXT NOT NULL,
                        agent_id TEXT NOT NULL,
                        agent_version_id TEXT,
                        title TEXT NOT NULL,
                        status TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        trigger_type TEXT NOT NULL,
                        trigger_ref TEXT,
                        chat_id TEXT,
                        idempotency_key TEXT,
                        retry_intent_json TEXT,
                        result_summary TEXT,
                        error_code TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            ensureColumn(connection, "enterprise_tasks", "retry_intent_json", "TEXT");
            ensureColumn(connection, "enterprise_tasks", "agent_version_id", "TEXT");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_task_runs (
                        run_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        attempt INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        agent_version_id TEXT,
                        started_at TEXT NOT NULL,
                        ended_at TEXT,
                        error_code TEXT,
                        safe_error TEXT,
                        input_tokens INTEGER NOT NULL DEFAULT 0,
                        output_tokens INTEGER NOT NULL DEFAULT 0,
                        UNIQUE(task_id, attempt),
                        FOREIGN KEY (task_id) REFERENCES enterprise_tasks(task_id) ON DELETE CASCADE
                    )
                    """);
            ensureColumn(connection, "enterprise_task_runs", "agent_version_id", "TEXT");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_task_events (
                        event_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        event_type TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        actor_type TEXT NOT NULL,
                        actor_id TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        visibility TEXT NOT NULL DEFAULT 'OWNER',
                        UNIQUE(run_id, sequence),
                        FOREIGN KEY (task_id) REFERENCES enterprise_tasks(task_id) ON DELETE CASCADE,
                        FOREIGN KEY (run_id) REFERENCES enterprise_task_runs(run_id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_task_idempotency "
                    + "ON enterprise_tasks(owner_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_owner_updated "
                    + "ON enterprise_tasks(owner_user_id, updated_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_status_updated "
                    + "ON enterprise_tasks(status, updated_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_session "
                    + "ON enterprise_tasks(owner_user_id, agent_id, session_id, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_event_task "
                    + "ON enterprise_task_events(task_id, occurred_at)");
        } catch (SQLException e) {
            throw databaseFailure("initialize task schema", e);
        }
    }

    private void recoverInterruptedRuns() {
        List<Map<String, Object>> active = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT t.task_id, r.run_id FROM enterprise_tasks t
                JOIN enterprise_task_runs r ON r.task_id = t.task_id
                WHERE t.status IN ('RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED')
                  AND r.state IN ('RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED')
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                active.add(Map.of("task_id", result.getString(1), "run_id", result.getString(2)));
            }
        } catch (SQLException e) {
            throw databaseFailure("scan interrupted tasks", e);
        }
        for (Map<String, Object> task : active) {
            transition(String.valueOf(task.get("task_id")), String.valueOf(task.get("run_id")),
                    "INTERRUPTED", "run_interrupted", Map.of("reason", "service restarted"),
                    null, "Service restarted before the run reached a terminal state", true);
        }
        if (!active.isEmpty()) log.warn("Marked {} unfinished task runs as INTERRUPTED", active.size());
    }

    private Map<String, Object> findActive(String ownerUserId, String agentId, String sessionId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT t.task_id, r.run_id, t.status FROM enterprise_tasks t
                JOIN enterprise_task_runs r ON r.task_id = t.task_id
                WHERE t.owner_user_id = ? AND t.agent_id = ? AND t.session_id = ?
                  AND t.status IN ('RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED')
                ORDER BY t.created_at DESC LIMIT 1
                """)) {
            statement.setString(1, required(ownerUserId, "owner user"));
            statement.setString(2, required(agentId, "Agent"));
            statement.setString(3, required(sessionId, "session"));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Map.of();
                return Map.of("task_id", result.getString(1), "run_id", result.getString(2),
                        "status", result.getString(3));
            }
        } catch (SQLException e) {
            throw databaseFailure("find active task", e);
        }
    }

    private boolean hasActiveRun(Connection connection, String owner, String agent, String session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM enterprise_tasks
                WHERE owner_user_id = ? AND agent_id = ? AND session_id = ?
                  AND status IN ('RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED')
                LIMIT 1
                """)) {
            statement.setString(1, owner);
            statement.setString(2, agent);
            statement.setString(3, session);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private TaskRun findByIdempotency(Connection connection, String owner, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.task_id, r.run_id FROM enterprise_tasks t
                JOIN enterprise_task_runs r ON r.task_id = t.task_id
                WHERE t.owner_user_id = ? AND t.idempotency_key = ?
                ORDER BY r.attempt DESC LIMIT 1
                """)) {
            statement.setString(1, owner);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new TaskRun(result.getString(1), result.getString(2), false) : null;
            }
        }
    }

    private boolean isCurrentNonTerminalRun(Connection connection, String taskId, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.status, r.state FROM enterprise_tasks t
                JOIN enterprise_task_runs r ON r.task_id = t.task_id
                WHERE t.task_id = ? AND r.run_id = ?
                  AND r.attempt = (SELECT MAX(r2.attempt) FROM enterprise_task_runs r2 WHERE r2.task_id = t.task_id)
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && !TERMINAL.contains(result.getString("status"))
                        && !TERMINAL.contains(result.getString("state"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private RetryIntent parseRetryIntent(String json) {
        if (blank(json)) return null;
        try {
            Map<String, Object> raw = JsonUtils.getMapper().readValue(json, LinkedHashMap.class);
            Object envRaw = raw.get("env");
            Map<String, Object> env = envRaw instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
            return new RetryIntent(String.valueOf(raw.get("mode")), String.valueOf(raw.get("message")), env,
                    Boolean.TRUE.equals(raw.get("internal")));
        } catch (Exception e) {
            log.warn("Ignoring invalid task retry intent", e);
            return null;
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void appendEvent(Connection connection, String taskId, String runId, String eventType,
                             String actorType, String actorId, Map<String, Object> payload) throws SQLException {
        int sequence;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM enterprise_task_events WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                sequence = result.next() ? result.getInt(1) : 1;
            }
        }
        String json = JsonUtils.toJson(payload == null ? Map.of() : payload);
        if (json.length() > MAX_EVENT_JSON_CHARS) {
            json = JsonUtils.toJson(Map.of(
                    "truncated", true,
                    "original_chars", json.length(),
                    "summary", "Event payload exceeded the storage limit"));
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO enterprise_task_events (
                    event_id, task_id, run_id, sequence, schema_version, event_type,
                    occurred_at, actor_type, actor_id, payload_json, visibility
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 'OWNER')
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, taskId);
            statement.setString(3, runId);
            statement.setInt(4, sequence);
            statement.setString(5, required(eventType, "event type"));
            statement.setString(6, Instant.now().toString());
            statement.setString(7, value(actorType, "SYSTEM"));
            statement.setString(8, value(actorId, "melonpaw"));
            statement.setString(9, json);
            statement.executeUpdate();
        }
    }

    private Map<String, Object> taskRow(ResultSet result) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : List.of("task_id", "task_type", "scope_type", "scope_id", "owner_user_id",
                "initiator_type", "initiator_id", "agent_id", "agent_version_id", "title", "status", "session_id",
                "trigger_type", "trigger_ref", "chat_id", "idempotency_key", "result_summary",
                "error_code", "created_at", "updated_at", "run_id", "attempt", "started_at", "ended_at",
                "input_tokens", "output_tokens", "safe_error")) {
            row.put(column, result.getObject(column));
        }
        row.put("retryable", RETRYABLE.contains(result.getString("status"))
                && !blank(result.getString("retry_intent_json")));
        return row;
    }

    private Map<String, Object> runRow(ResultSet result) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : List.of("run_id", "task_id", "attempt", "state", "phase", "started_at",
                "agent_version_id", "ended_at", "error_code", "safe_error", "input_tokens", "output_tokens")) {
            row.put(column, result.getObject(column));
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventRow(ResultSet result) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : List.of("event_id", "task_id", "run_id", "sequence", "schema_version",
                "event_type", "occurred_at", "actor_type", "actor_id", "visibility")) {
            row.put(column, result.getObject(column));
        }
        try {
            row.put("payload", JsonUtils.getMapper().readValue(result.getString("payload_json"), LinkedHashMap.class));
        } catch (Exception e) {
            row.put("payload", Map.of());
        }
        return row;
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) statement.setObject(i + 1, params.get(i));
    }

    private static Path resolveDatabasePath(String configuredPath, String homeDir) {
        if (!blank(configuredPath)) return expandHome(configuredPath.trim());
        return expandHome(blank(homeDir) ? "~/.melonAI" : homeDir).resolve("enterprise.db");
    }

    private static Path expandHome(String value) {
        if ("~".equals(value)) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value);
    }

    private static String required(String value, String name) {
        if (blank(value)) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String normalized(String value, String fallback) {
        return EnterpriseTaskService.value(value, fallback).trim().toUpperCase(Locale.ROOT);
    }

    private static String value(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String limited(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private IllegalStateException databaseFailure(String operation, SQLException error) {
        return new IllegalStateException("Failed to " + operation + ": " + error.getMessage(), error);
    }

    public record TaskRun(String taskId, String runId, boolean created) {
    }

    public static final class AgentMutationInProgressException extends IllegalStateException {

        public AgentMutationInProgressException(String message) {
            super(message);
        }
    }

    public record RetryStart(TaskRun run, RetryIntent intent) {
    }

    public record RetryIntent(String mode, String message, Map<String, Object> env, boolean internal) {

        public RetryIntent(String mode, String message, Map<String, Object> env) {
            this(mode, message, env, false);
        }

        public RetryIntent {
            mode = normalized(mode, "STREAM");
            if (!RETRY_MODES.contains(mode)) throw new IllegalArgumentException("Unsupported retry mode");
            message = required(message, "retry message");
            env = env == null ? Map.of() : new LinkedHashMap<>(env);
        }
    }
}
