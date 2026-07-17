package com.melon.app.service;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.core.util.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Append-only, hash-chained audit ledger for enterprise management operations. */
@Service
public class EnterpriseAuditService {

    private static final int MAX_JSON_CHARS = 64_000;
    private static final Set<String> SECRET_NAMES = Set.of(
            "password", "api_key", "apikey", "secret", "client_secret", "app_secret",
            "bot_token", "access_token", "refresh_token", "authorization", "cookie",
            "credentials", "env", "headers", "custom_headers", "private_key");

    private final String jdbcUrl;

    public EnterpriseAuditService(
            @Value("${melon.enterprise.database-path:}") String configuredPath,
            @Value("${melon.home_dir:~/.melonAI}") String homeDir) {
        Path database = resolveDatabasePath(configuredPath, homeDir);
        try {
            Path parent = database.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create enterprise audit database directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        initialize();
    }

    public synchronized void record(EnterprisePrincipal actor, String action,
                                    String targetType, String targetId,
                                    String requestId, String sourceIp, String result,
                                    Object before, Object after, Map<String, Object> metadata) {
        String eventId = UUID.randomUUID().toString();
        String occurredAt = Instant.now().toString();
        String actorId = actor != null ? actor.id() : "system";
        String actorName = actor != null ? actor.username() : "system";
        String beforeJson = safeJson(before);
        String afterJson = safeJson(after);
        String metadataJson = safeJson(metadata);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String previousHash = lastHash(connection);
                String eventHash = hash(String.join("\n",
                        previousHash, eventId, occurredAt, actorId, actorName,
                        value(action), value(targetType), value(targetId), value(requestId),
                        value(sourceIp), value(result), beforeJson, afterJson, metadataJson));
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO enterprise_management_audit (
                            event_id, occurred_at, actor_user_id, actor_username, action,
                            target_type, target_id, request_id, source_ip, result,
                            before_json, after_json, metadata_json, previous_hash, event_hash
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    int i = 1;
                    statement.setString(i++, eventId);
                    statement.setString(i++, occurredAt);
                    statement.setString(i++, actorId);
                    statement.setString(i++, actorName);
                    statement.setString(i++, value(action));
                    statement.setString(i++, value(targetType));
                    statement.setString(i++, value(targetId));
                    statement.setString(i++, value(requestId));
                    statement.setString(i++, value(sourceIp));
                    statement.setString(i++, value(result));
                    statement.setString(i++, beforeJson);
                    statement.setString(i++, afterJson);
                    statement.setString(i++, metadataJson);
                    statement.setString(i++, previousHash);
                    statement.setString(i, eventHash);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write enterprise audit event", e);
        }
    }

    public List<Map<String, Object>> query(String actorUserId, String action, String targetType,
                                            String result, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM enterprise_management_audit WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, "actor_user_id", actorUserId);
        appendFilter(sql, args, "action", action);
        appendFilter(sql, args, "target_type", targetType);
        appendFilter(sql, args, "result", result);
        sql.append(" ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        args.add(Math.max(0, offset));
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) statement.setObject(i + 1, args.get(i));
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> resultRows = new ArrayList<>();
                while (rows.next()) resultRows.add(row(rows));
                return resultRows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query enterprise audit events", e);
        }
    }

    public Map<String, Object> verifyChain() {
        String expectedPrevious = "GENESIS";
        int count = 0;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM enterprise_management_audit ORDER BY rowid");
             ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                count++;
                String eventId = row.getString("event_id");
                String previousHash = row.getString("previous_hash");
                String expectedHash = hash(String.join("\n",
                        previousHash,
                        eventId,
                        row.getString("occurred_at"),
                        row.getString("actor_user_id"),
                        row.getString("actor_username"),
                        row.getString("action"),
                        row.getString("target_type"),
                        row.getString("target_id"),
                        row.getString("request_id"),
                        row.getString("source_ip"),
                        row.getString("result"),
                        row.getString("before_json"),
                        row.getString("after_json"),
                        row.getString("metadata_json")));
                if (!expectedPrevious.equals(previousHash) || !expectedHash.equals(row.getString("event_hash"))) {
                    return Map.of(
                            "valid", false,
                            "events_checked", count,
                            "broken_event_id", eventId);
                }
                expectedPrevious = expectedHash;
            }
            return Map.of(
                    "valid", true,
                    "events_checked", count,
                    "last_hash", expectedPrevious);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to verify enterprise audit chain", e);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_management_audit (
                        event_id TEXT PRIMARY KEY,
                        occurred_at TEXT NOT NULL,
                        actor_user_id TEXT NOT NULL,
                        actor_username TEXT NOT NULL,
                        action TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        source_ip TEXT NOT NULL,
                        result TEXT NOT NULL,
                        before_json TEXT NOT NULL DEFAULT '{}',
                        after_json TEXT NOT NULL DEFAULT '{}',
                        metadata_json TEXT NOT NULL DEFAULT '{}',
                        previous_hash TEXT NOT NULL,
                        event_hash TEXT NOT NULL UNIQUE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_management_audit_time "
                    + "ON enterprise_management_audit(occurred_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_management_audit_actor "
                    + "ON enterprise_management_audit(actor_user_id, occurred_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_management_audit_target "
                    + "ON enterprise_management_audit(target_type, target_id, occurred_at DESC)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize enterprise audit schema", e);
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

    private String lastHash(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_hash FROM enterprise_management_audit ORDER BY rowid DESC LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : "GENESIS";
        }
    }

    private Map<String, Object> row(ResultSet result) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : List.of(
                "event_id", "occurred_at", "actor_user_id", "actor_username", "action",
                "target_type", "target_id", "request_id", "source_ip", "result",
                "previous_hash", "event_hash")) {
            row.put(column, result.getObject(column));
        }
        row.put("before", parseJson(result.getString("before_json")));
        row.put("after", parseJson(result.getString("after_json")));
        row.put("metadata", parseJson(result.getString("metadata_json")));
        return row;
    }

    private Object parseJson(String value) {
        try {
            return JsonUtils.getMapper().readValue(value, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String safeJson(Object value) {
        String json = JsonUtils.toJson(redact(value == null ? Map.of() : value));
        if (json.length() <= MAX_JSON_CHARS) return json;
        return JsonUtils.toJson(Map.of("truncated", true, "original_chars", json.length()));
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String name = String.valueOf(key);
                result.put(name, secretName(name) ? "***" : redact(child));
            });
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(child -> result.add(redact(child)));
            return result;
        }
        return value;
    }

    private boolean secretName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).replace('-', '_');
        return SECRET_NAMES.contains(normalized)
                || normalized.endsWith("_secret")
                || normalized.endsWith("_password")
                || normalized.endsWith("_token")
                || normalized.endsWith("_api_key");
    }

    private void appendFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND ").append(column).append(" = ?");
        args.add(value.trim());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash enterprise audit event", e);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
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
}
