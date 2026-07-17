package com.melon.app.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.BuiltinToolConfig;
import com.melon.core.config.ToolsConfig;
import com.melon.core.util.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;
import java.util.UUID;

/** Persistent Agent definitions and immutable published configuration versions. */
@Service
public class EnterpriseAgentDefinitionService {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String jdbcUrl;

    @Autowired
    public EnterpriseAgentDefinitionService(
            @Value("${melon.enterprise.database-path:}") String configuredPath,
            @Value("${melon.home_dir:~/.melonAI}") String homeDir) {
        this(resolveDatabasePath(configuredPath, homeDir));
    }

    public EnterpriseAgentDefinitionService(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create Agent definition database directory", e);
        }
        jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        initialize();
    }

    public synchronized AgentDefinition publish(String agentId, String scopeType, String scopeId,
                                                AgentConfig config, String actorUserId,
                                                String changeSummary) {
        if (config == null) throw new IllegalArgumentException("Agent configuration is required");
        String aid = required(agentId, "Agent id");
        String scope = required(scopeType, "scope type").toUpperCase(Locale.ROOT);
        String owner = required(scopeId, "scope id");
        String actor = required(actorUserId, "publisher");
        String now = Instant.now().toString();
        String snapshot = JsonUtils.toJson(snapshot(config));
        String hash = sha256(snapshot);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                AgentDefinition existing = findByAgent(connection, aid).orElse(null);
                if (existing != null
                        && (!scope.equals(existing.scopeType()) || !owner.equals(existing.scopeId()))) {
                    throw new IllegalStateException("An Agent definition cannot change its resource scope");
                }
                String definitionId = existing != null ? existing.definitionId() : UUID.randomUUID().toString();
                if (existing == null) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO enterprise_agent_definitions (
                                definition_id, agent_id, scope_type, scope_id, name, description,
                                lifecycle_state, current_version_id, created_by, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', NULL, ?, ?, ?)
                            """)) {
                        statement.setString(1, definitionId);
                        statement.setString(2, aid);
                        statement.setString(3, scope);
                        statement.setString(4, owner);
                        statement.setString(5, value(config.getName(), aid));
                        statement.setString(6, value(config.getDescription(), ""));
                        statement.setString(7, actor);
                        statement.setString(8, now);
                        statement.setString(9, now);
                        statement.executeUpdate();
                    }
                }
                int versionNumber = nextVersion(connection, definitionId);
                String versionId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO enterprise_agent_versions (
                            version_id, definition_id, version_number, config_snapshot_json,
                            config_sha256, change_summary, published_by, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, versionId);
                    statement.setString(2, definitionId);
                    statement.setInt(3, versionNumber);
                    statement.setString(4, snapshot);
                    statement.setString(5, hash);
                    statement.setString(6, value(changeSummary, "Configuration published"));
                    statement.setString(7, actor);
                    statement.setString(8, now);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE enterprise_agent_definitions
                        SET name = ?, description = ?, lifecycle_state = 'PUBLISHED',
                            current_version_id = ?, updated_at = ?
                        WHERE definition_id = ?
                        """)) {
                    statement.setString(1, value(config.getName(), aid));
                    statement.setString(2, value(config.getDescription(), ""));
                    statement.setString(3, versionId);
                    statement.setString(4, now);
                    statement.setString(5, definitionId);
                    statement.executeUpdate();
                }
                AgentDefinition published = findByAgent(connection, aid).orElseThrow();
                connection.commit();
                return published;
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("publish Agent version", e);
        }
    }

    public synchronized Optional<AgentDefinition> findByAgent(String agentId) {
        try (Connection connection = open()) {
            return findByAgent(connection, agentId);
        } catch (SQLException e) {
            throw databaseFailure("find Agent definition", e);
        }
    }

    public synchronized Optional<AgentDefinition> findProjectAgent(String projectId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT d.*, v.version_number FROM enterprise_agent_definitions d
                LEFT JOIN enterprise_agent_versions v ON v.version_id = d.current_version_id
                WHERE d.scope_type = 'PROJECT' AND d.scope_id = ? AND d.lifecycle_state <> 'ARCHIVED'
                """)) {
            statement.setString(1, required(projectId, "project id"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(definitionRow(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw databaseFailure("find project Agent definition", e);
        }
    }

    public synchronized String currentVersionId(String agentId) {
        return findByAgent(agentId).map(AgentDefinition::currentVersionId).orElse(null);
    }

    public synchronized List<AgentVersion> versions(String agentId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT v.* FROM enterprise_agent_versions v
                JOIN enterprise_agent_definitions d ON d.definition_id = v.definition_id
                WHERE d.agent_id = ? ORDER BY v.version_number DESC
                """)) {
            statement.setString(1, required(agentId, "Agent id"));
            try (ResultSet result = statement.executeQuery()) {
                List<AgentVersion> versions = new ArrayList<>();
                while (result.next()) versions.add(versionRow(result));
                return versions;
            }
        } catch (SQLException e) {
            throw databaseFailure("list Agent versions", e);
        }
    }

    public synchronized AgentConfig versionConfig(String agentId, String versionId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT v.config_snapshot_json FROM enterprise_agent_versions v
                JOIN enterprise_agent_definitions d ON d.definition_id = v.definition_id
                WHERE d.agent_id = ? AND v.version_id = ?
                """)) {
            statement.setString(1, required(agentId, "Agent id"));
            statement.setString(2, required(versionId, "version id"));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Agent version not found");
                Map<String, Object> values = JsonUtils.getMapper().readValue(
                        result.getString("config_snapshot_json"), MAP_TYPE);
                return JsonUtils.getMapper().convertValue(values, AgentConfig.class);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Invalid Agent version snapshot", e);
            }
        } catch (SQLException e) {
            throw databaseFailure("load Agent version", e);
        }
    }

    private Optional<AgentDefinition> findByAgent(Connection connection, String agentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.*, v.version_number FROM enterprise_agent_definitions d
                LEFT JOIN enterprise_agent_versions v ON v.version_id = d.current_version_id
                WHERE d.agent_id = ?
                """)) {
            statement.setString(1, required(agentId, "Agent id"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(definitionRow(result)) : Optional.empty();
            }
        }
    }

    private int nextVersion(Connection connection, String definitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(version_number), 0) + 1 FROM enterprise_agent_versions WHERE definition_id = ?")) {
            statement.setString(1, definitionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 1;
            }
        }
    }

    private Map<String, Object> snapshot(AgentConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", config.getName());
        values.put("description", config.getDescription());
        values.put("enabled", config.isEnabled());
        values.put("active_model", config.getActiveModel());
        values.put("system_prompt_files", config.getSystemPromptFiles());
        values.put("skills", config.getSkills());
        values.put("tools", toolPolicy(config.getTools()));
        values.put("running", config.getRunning());
        values.put("context_compact", config.getContextCompact());
        values.put("light_context_config", config.getLightContextConfig());
        values.put("memory_manager_backend", config.getMemoryManagerBackend());
        values.put("plan_mode", config.getPlanMode());
        values.put("approval", config.getApproval());
        values.put("heartbeat", config.getHeartbeat());
        values.put("language", config.getLanguage());
        values.put("timezone", config.getTimezone());
        values.put("frontend_running_config", config.getFrontendRunningConfig());
        return values;
    }

    private Map<String, Object> toolPolicy(ToolsConfig tools) {
        if (tools == null) return Map.of();
        Map<String, Object> builtin = new LinkedHashMap<>();
        for (Map.Entry<String, BuiltinToolConfig> entry : (tools.getBuiltinTools() != null
                ? tools.getBuiltinTools() : Map.<String, BuiltinToolConfig>of()).entrySet()) {
            BuiltinToolConfig tool = entry.getValue();
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("name", tool.getName());
            policy.put("enabled", tool.isEnabled());
            policy.put("description", tool.getDescription());
            policy.put("display_to_user", tool.isDisplayToUser());
            policy.put("async_execution", tool.isAsyncExecution());
            policy.put("icon", tool.getIcon());
            policy.put("requires_config", tool.isRequiresConfig());
            policy.put("config_fields", tool.getConfigFields());
            builtin.put(entry.getKey(), policy);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("builtin_tools", builtin);
        result.put("async_execution_tools",
                tools.getAsyncExecutionTools() != null ? tools.getAsyncExecutionTools() : Map.of());
        return result;
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_agent_definitions (
                        definition_id TEXT PRIMARY KEY,
                        agent_id TEXT NOT NULL UNIQUE,
                        scope_type TEXT NOT NULL,
                        scope_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        lifecycle_state TEXT NOT NULL,
                        current_version_id TEXT,
                        created_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_agent_versions (
                        version_id TEXT PRIMARY KEY,
                        definition_id TEXT NOT NULL,
                        version_number INTEGER NOT NULL,
                        config_snapshot_json TEXT NOT NULL,
                        config_sha256 TEXT NOT NULL,
                        change_summary TEXT NOT NULL DEFAULT '',
                        published_by TEXT NOT NULL,
                        published_at TEXT NOT NULL,
                        UNIQUE(definition_id, version_number),
                        FOREIGN KEY (definition_id) REFERENCES enterprise_agent_definitions(definition_id)
                            ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_definition_scope "
                    + "ON enterprise_agent_definitions(scope_type, scope_id)");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_project_agent_definition "
                    + "ON enterprise_agent_definitions(scope_id) "
                    + "WHERE scope_type = 'PROJECT' AND lifecycle_state <> 'ARCHIVED'");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_version_definition "
                    + "ON enterprise_agent_versions(definition_id, version_number DESC)");
        } catch (SQLException e) {
            throw databaseFailure("initialize Agent definition schema", e);
        }
    }

    private AgentDefinition definitionRow(ResultSet result) throws SQLException {
        return new AgentDefinition(
                result.getString("definition_id"),
                result.getString("agent_id"),
                result.getString("scope_type"),
                result.getString("scope_id"),
                result.getString("name"),
                result.getString("description"),
                result.getString("lifecycle_state"),
                result.getString("current_version_id"),
                result.getInt("version_number"),
                result.getString("created_by"),
                result.getString("created_at"),
                result.getString("updated_at"));
    }

    private AgentVersion versionRow(ResultSet result) throws SQLException {
        return new AgentVersion(
                result.getString("version_id"),
                result.getInt("version_number"),
                result.getString("config_sha256"),
                result.getString("change_summary"),
                result.getString("published_by"),
                result.getString("published_at"));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private static Path resolveDatabasePath(String configuredPath, String homeDir) {
        if (configuredPath != null && !configuredPath.isBlank()) return expandHome(configuredPath.trim());
        return expandHome(homeDir == null || homeDir.isBlank() ? "~/.melonAI" : homeDir).resolve("enterprise.db");
    }

    private static Path expandHome(String value) {
        if ("~".equals(value)) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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

    public record AgentDefinition(
            String definitionId,
            String agentId,
            String scopeType,
            String scopeId,
            String name,
            String description,
            String lifecycleState,
            String currentVersionId,
            int currentVersion,
            String createdBy,
            String createdAt,
            String updatedAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("definition_id", definitionId);
            result.put("agent_id", agentId);
            result.put("scope_type", scopeType);
            result.put("scope_id", scopeId);
            result.put("name", name);
            result.put("description", description);
            result.put("lifecycle_state", lifecycleState);
            result.put("current_version_id", currentVersionId);
            result.put("current_version", currentVersion);
            result.put("created_by", createdBy);
            result.put("created_at", createdAt);
            result.put("updated_at", updatedAt);
            return result;
        }
    }

    public record AgentVersion(
            String versionId,
            int version,
            String configSha256,
            String changeSummary,
            String publishedBy,
            String publishedAt) {

        public Map<String, Object> toMap() {
            return Map.of(
                    "version_id", versionId,
                    "version", version,
                    "config_sha256", configSha256,
                    "change_summary", changeSummary,
                    "published_by", publishedBy,
                    "published_at", publishedAt);
        }
    }
}
