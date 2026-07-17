package com.melon.app.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Persistent quota policies and admission leases shared by every task entry point. */
@Service
public class EnterpriseQuotaService {

    private static final Set<String> SCOPES = Set.of("ENTERPRISE", "USER", "PROJECT", "AGENT");
    private static final Map<String, QuotaPolicy> DEFAULTS = Map.of(
            "ENTERPRISE", new QuotaPolicy("ENTERPRISE", "default", 20, 0, 0, 0, 3600, "system", ""),
            "USER", new QuotaPolicy("USER", "*", 3, 0, 0, 0, 3600, "system", ""),
            "PROJECT", new QuotaPolicy("PROJECT", "*", 5, 0, 0, 0, 3600, "system", ""),
            "AGENT", new QuotaPolicy("AGENT", "*", 5, 0, 0, 0, 3600, "system", ""));

    private final String jdbcUrl;

    @Autowired
    public EnterpriseQuotaService(
            @Value("${melon.enterprise.database-path:}") String configuredPath,
            @Value("${melon.home_dir:~/.melonAI}") String homeDir) {
        this(resolveDatabasePath(configuredPath, homeDir));
    }

    EnterpriseQuotaService(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create quota database directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        initialize();
    }

    public synchronized AdmissionLease reserve(String taskId, String runId, String taskType,
                                                String userId, String scopeType, String scopeId,
                                                String agentId) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                AdmissionLease lease = reserve(connection, taskId, runId, taskType,
                        userId, scopeType, scopeId, agentId);
                connection.commit();
                return lease;
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reserve task capacity", e);
        }
    }

    synchronized AdmissionLease reserve(Connection connection, String taskId, String runId, String taskType,
                                        String userId, String scopeType, String scopeId,
                                        String agentId) throws SQLException {
        String projectId = "PROJECT".equals(scopeType) ? scopeId : null;
        List<QuotaPolicy> policies = effectivePolicies(userId, projectId, agentId);
        String dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        expireLeases(connection);
        for (QuotaPolicy policy : policies) {
            enforcePolicy(connection, policy, userId, projectId, agentId, dayStart);
        }
        int maxTokens = minPositive(policies.stream().map(QuotaPolicy::taskTokenLimit).toList());
        int runtimeSeconds = minPositive(policies.stream().map(QuotaPolicy::maxRuntimeSeconds).toList());
        if (runtimeSeconds <= 0) runtimeSeconds = 3600;
        String acquiredAt = Instant.now().toString();
        String expiresAt = Instant.now().plusSeconds(runtimeSeconds).toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO enterprise_admission_leases (
                    run_id, task_id, task_type, user_id, project_id, agent_id,
                    token_limit, acquired_at, expires_at, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """)) {
            statement.setString(1, required(runId, "run id"));
            statement.setString(2, required(taskId, "task id"));
            statement.setString(3, normalized(taskType, "AGENT"));
            statement.setString(4, required(userId, "user id"));
            statement.setString(5, blankToNull(projectId));
            statement.setString(6, required(agentId, "Agent"));
            statement.setInt(7, maxTokens);
            statement.setString(8, acquiredAt);
            statement.setString(9, expiresAt);
            statement.executeUpdate();
        }
        return new AdmissionLease(runId, expiresAt, maxTokens, runtimeSeconds);
    }

    public synchronized void release(String runId, String outcome) {
        if (runId == null || runId.isBlank()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE enterprise_admission_leases
                SET status = 'RELEASED', released_at = ?, outcome = ?
                WHERE run_id = ? AND status = 'ACTIVE'
                """)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, value(outcome, "COMPLETED"));
            statement.setString(3, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to release task capacity", e);
        }
    }

    /** Releases leases whose Task/Run is no longer active after a service restart. */
    public synchronized void reconcile() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            expireLeases(connection);
            statement.executeUpdate("""
                    UPDATE enterprise_admission_leases
                    SET status = 'RELEASED', released_at = CURRENT_TIMESTAMP, outcome = 'RECONCILED'
                    WHERE status = 'ACTIVE' AND NOT EXISTS (
                        SELECT 1 FROM enterprise_task_runs r
                        WHERE r.run_id = enterprise_admission_leases.run_id
                          AND r.state IN ('RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED'))
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reconcile task admission leases", e);
        }
    }

    public synchronized boolean tokenLimitExceeded(String runId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT l.token_limit, r.input_tokens + r.output_tokens AS used_tokens
                FROM enterprise_admission_leases l
                JOIN enterprise_task_runs r ON r.run_id = l.run_id
                WHERE l.run_id = ?
                """)) {
            statement.setString(1, required(runId, "run id"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt("token_limit") > 0
                        && result.getLong("used_tokens") > result.getInt("token_limit");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check task token budget", e);
        }
    }

    public synchronized List<Map<String, Object>> listPolicies() {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM enterprise_quota_policies ORDER BY scope_type, scope_id");
             ResultSet result = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.next()) rows.add(policy(result).toMap());
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list quota policies", e);
        }
    }

    public Map<String, Object> defaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        DEFAULTS.forEach((scope, policy) -> result.put(scope, policy.toMap()));
        return result;
    }

    public synchronized Map<String, Object> savePolicy(String scopeType, String scopeId,
                                                        Map<String, Object> body, String actorUserId) {
        String type = normalizedScope(scopeType);
        String id = "ENTERPRISE".equals(type) ? "default" : required(scopeId, "scope id");
        QuotaPolicy fallback = policyOrDefault(type, id);
        QuotaPolicy value = new QuotaPolicy(
                type,
                id,
                nonNegative(body, "max_concurrent", fallback.maxConcurrent()),
                nonNegative(body, "daily_task_limit", fallback.dailyTaskLimit()),
                nonNegative(body, "daily_token_limit", fallback.dailyTokenLimit()),
                nonNegative(body, "task_token_limit", fallback.taskTokenLimit()),
                nonNegative(body, "max_runtime_seconds", fallback.maxRuntimeSeconds()),
                required(actorUserId, "actor user"),
                Instant.now().toString());
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO enterprise_quota_policies (
                    scope_type, scope_id, max_concurrent, daily_task_limit, daily_token_limit,
                    task_token_limit, max_runtime_seconds, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(scope_type, scope_id) DO UPDATE SET
                    max_concurrent=excluded.max_concurrent,
                    daily_task_limit=excluded.daily_task_limit,
                    daily_token_limit=excluded.daily_token_limit,
                    task_token_limit=excluded.task_token_limit,
                    max_runtime_seconds=excluded.max_runtime_seconds,
                    updated_by=excluded.updated_by,
                    updated_at=excluded.updated_at
                """)) {
            bindPolicy(statement, value);
            statement.executeUpdate();
            return value.toMap();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save quota policy", e);
        }
    }

    public synchronized boolean deletePolicy(String scopeType, String scopeId) {
        String type = normalizedScope(scopeType);
        String id = "ENTERPRISE".equals(type) ? "default" : required(scopeId, "scope id");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM enterprise_quota_policies WHERE scope_type = ? AND scope_id = ?")) {
            statement.setString(1, type);
            statement.setString(2, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete quota policy", e);
        }
    }

    private List<QuotaPolicy> effectivePolicies(String userId, String projectId, String agentId) {
        List<QuotaPolicy> policies = new ArrayList<>();
        policies.add(policyOrDefault("ENTERPRISE", "default"));
        policies.add(policyOrDefault("USER", userId));
        if (projectId != null && !projectId.isBlank()) policies.add(policyOrDefault("PROJECT", projectId));
        policies.add(policyOrDefault("AGENT", agentId));
        return policies;
    }

    private QuotaPolicy policyOrDefault(String scopeType, String scopeId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM enterprise_quota_policies WHERE scope_type = ? AND scope_id = ?")) {
            statement.setString(1, scopeType);
            statement.setString(2, scopeId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return policy(result);
            }
            QuotaPolicy fallback = DEFAULTS.get(scopeType);
            return new QuotaPolicy(scopeType, scopeId, fallback.maxConcurrent(), fallback.dailyTaskLimit(),
                    fallback.dailyTokenLimit(), fallback.taskTokenLimit(), fallback.maxRuntimeSeconds(),
                    fallback.updatedBy(), fallback.updatedAt());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve quota policy", e);
        }
    }

    private void enforcePolicy(Connection connection, QuotaPolicy policy, String userId,
                               String projectId, String agentId, String dayStart) throws SQLException {
        ScopePredicate predicate = predicate(policy.scopeType(), policy.scopeId(), userId, projectId, agentId);
        if (policy.maxConcurrent() > 0) {
            long active = scalar(connection,
                    "SELECT COUNT(*) FROM enterprise_admission_leases WHERE status = 'ACTIVE'"
                            + predicate.leaseSql(), predicate.value());
            if (active >= policy.maxConcurrent()) {
                throw new QuotaExceededException(policy.scopeType(), "max_concurrent",
                        "Concurrent task limit reached for " + policy.scopeType().toLowerCase(Locale.ROOT));
            }
        }
        if (policy.dailyTaskLimit() > 0) {
            long tasks = scalar(connection,
                    "SELECT COUNT(*) FROM enterprise_tasks t WHERE t.created_at >= ?"
                            + predicate.taskSql(), dayStart, predicate.value());
            if (tasks >= policy.dailyTaskLimit()) {
                throw new QuotaExceededException(policy.scopeType(), "daily_task_limit",
                        "Daily task limit reached for " + policy.scopeType().toLowerCase(Locale.ROOT));
            }
        }
        if (policy.dailyTokenLimit() > 0) {
            long tokens = scalar(connection,
                    "SELECT COALESCE(SUM(r.input_tokens + r.output_tokens), 0) "
                            + "FROM enterprise_task_runs r JOIN enterprise_tasks t ON t.task_id = r.task_id "
                            + "WHERE r.started_at >= ?" + predicate.taskSql(), dayStart, predicate.value());
            if (tokens >= policy.dailyTokenLimit()) {
                throw new QuotaExceededException(policy.scopeType(), "daily_token_limit",
                        "Daily token limit reached for " + policy.scopeType().toLowerCase(Locale.ROOT));
            }
        }
    }

    private ScopePredicate predicate(String policyType, String policyId,
                                     String userId, String projectId, String agentId) {
        return switch (policyType) {
            case "USER" -> new ScopePredicate(" AND user_id = ?", " AND t.owner_user_id = ?", userId);
            case "PROJECT" -> new ScopePredicate(" AND project_id = ?",
                    " AND t.scope_type = 'PROJECT' AND t.scope_id = ?", projectId);
            case "AGENT" -> new ScopePredicate(" AND agent_id = ?", " AND t.agent_id = ?", agentId);
            default -> new ScopePredicate("", "", null);
        };
    }

    private long scalar(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object arg : args) {
                if (arg != null) statement.setObject(index++, arg);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private void expireLeases(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE enterprise_admission_leases
                SET status = 'EXPIRED', released_at = ?, outcome = 'TIMEOUT'
                WHERE status = 'ACTIVE' AND expires_at <= ?
                """)) {
            String now = Instant.now().toString();
            statement.setString(1, now);
            statement.setString(2, now);
            statement.executeUpdate();
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_quota_policies (
                        scope_type TEXT NOT NULL,
                        scope_id TEXT NOT NULL,
                        max_concurrent INTEGER NOT NULL DEFAULT 0,
                        daily_task_limit INTEGER NOT NULL DEFAULT 0,
                        daily_token_limit INTEGER NOT NULL DEFAULT 0,
                        task_token_limit INTEGER NOT NULL DEFAULT 0,
                        max_runtime_seconds INTEGER NOT NULL DEFAULT 3600,
                        updated_by TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(scope_type, scope_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_admission_leases (
                        run_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        task_type TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        project_id TEXT,
                        agent_id TEXT NOT NULL,
                        token_limit INTEGER NOT NULL DEFAULT 0,
                        acquired_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        released_at TEXT,
                        status TEXT NOT NULL,
                        outcome TEXT
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admission_active "
                    + "ON enterprise_admission_leases(status, expires_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admission_user "
                    + "ON enterprise_admission_leases(user_id, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admission_project "
                    + "ON enterprise_admission_leases(project_id, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admission_agent "
                    + "ON enterprise_admission_leases(agent_id, status)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize quota schema", e);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private QuotaPolicy policy(ResultSet result) throws SQLException {
        return new QuotaPolicy(
                result.getString("scope_type"), result.getString("scope_id"),
                result.getInt("max_concurrent"), result.getInt("daily_task_limit"),
                result.getInt("daily_token_limit"), result.getInt("task_token_limit"),
                result.getInt("max_runtime_seconds"), result.getString("updated_by"),
                result.getString("updated_at"));
    }

    private void bindPolicy(PreparedStatement statement, QuotaPolicy policy) throws SQLException {
        statement.setString(1, policy.scopeType());
        statement.setString(2, policy.scopeId());
        statement.setInt(3, policy.maxConcurrent());
        statement.setInt(4, policy.dailyTaskLimit());
        statement.setInt(5, policy.dailyTokenLimit());
        statement.setInt(6, policy.taskTokenLimit());
        statement.setInt(7, policy.maxRuntimeSeconds());
        statement.setString(8, policy.updatedBy());
        statement.setString(9, policy.updatedAt());
    }

    private int nonNegative(Map<String, Object> body, String key, int fallback) {
        Object value = body != null ? body.get(key) : null;
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a non-negative integer");
        }
    }

    private int minPositive(List<Integer> values) {
        return values.stream().filter(value -> value != null && value > 0)
                .min(Integer::compareTo).orElse(0);
    }

    private String normalizedScope(String value) {
        String result = normalized(value, "");
        if (!SCOPES.contains(result)) throw new IllegalArgumentException("Unsupported quota scope");
        return result;
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static Path resolveDatabasePath(String configuredPath, String homeDir) {
        if (configuredPath != null && !configuredPath.isBlank()) return expandHome(configuredPath.trim());
        return expandHome(homeDir == null || homeDir.isBlank() ? "~/.melonAI" : homeDir)
                .resolve("enterprise.db");
    }

    private static Path expandHome(String value) {
        if ("~".equals(value)) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value);
    }

    public record AdmissionLease(String runId, String expiresAt, int tokenLimit, int runtimeSeconds) {
    }

    public record QuotaPolicy(String scopeType, String scopeId, int maxConcurrent,
                              int dailyTaskLimit, int dailyTokenLimit, int taskTokenLimit,
                              int maxRuntimeSeconds, String updatedBy, String updatedAt) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scope_type", scopeType);
            result.put("scope_id", scopeId);
            result.put("max_concurrent", maxConcurrent);
            result.put("daily_task_limit", dailyTaskLimit);
            result.put("daily_token_limit", dailyTokenLimit);
            result.put("task_token_limit", taskTokenLimit);
            result.put("max_runtime_seconds", maxRuntimeSeconds);
            result.put("updated_by", updatedBy);
            result.put("updated_at", updatedAt);
            return result;
        }
    }

    private record ScopePredicate(String leaseSql, String taskSql, String value) {
    }

    public static final class QuotaExceededException extends IllegalStateException {
        private final String scopeType;
        private final String limitType;

        public QuotaExceededException(String scopeType, String limitType, String message) {
            super(message);
            this.scopeType = scopeType;
            this.limitType = limitType;
        }

        public String scopeType() {
            return scopeType;
        }

        public String limitType() {
            return limitType;
        }
    }
}
