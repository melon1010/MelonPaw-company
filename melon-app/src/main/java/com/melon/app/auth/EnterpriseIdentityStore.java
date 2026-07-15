package com.melon.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite-backed identity, RBAC, session and project-space repository.
 *
 * <p>The repository deliberately uses a small JDBC surface so the private deployment remains a
 * single-JAR application. All writes are serialized in-process and SQLite's busy timeout protects
 * against short-lived contention from another application process.</p>
 */
@Repository
public class EnterpriseIdentityStore {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseIdentityStore.class);

    private static final Set<String> PROJECT_ROLES = Set.of("OWNER", "MANAGER", "MEMBER", "VIEWER");

    private final String jdbcUrl;

    @Autowired
    public EnterpriseIdentityStore(
            @Value("${melon.enterprise.database-path:}") String configuredPath,
            @Value("${melon.home_dir:~/.melonAI}") String homeDir) {
        this(resolveDatabasePath(configuredPath, homeDir));
    }

    public EnterpriseIdentityStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create enterprise database directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        initialize();
    }

    public synchronized long countUsers() {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM enterprise_users")) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (SQLException e) {
            throw databaseFailure("count users", e);
        }
    }

    public synchronized EnterprisePrincipal bootstrapAdmin(
            String username, String displayName, String passwordHash) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (scalarLong(connection, "SELECT COUNT(*) FROM enterprise_users") != 0L) {
                    throw new IllegalStateException("The initial administrator has already been created");
                }
                String userId = UUID.randomUUID().toString();
                insertUser(connection, userId, username, displayName, passwordHash, true);
                insertUserRole(connection, userId, "SYSTEM_ADMIN");
                connection.commit();
                return requireUser(connection, userId).toPrincipal();
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("bootstrap administrator", e);
        }
    }

    public synchronized Optional<StoredUser> findUserByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = open()) {
            return findUser(connection, "LOWER(username) = LOWER(?)", username.trim());
        } catch (SQLException e) {
            throw databaseFailure("find user by username", e);
        }
    }

    public synchronized Optional<EnterprisePrincipal> findUserById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = open()) {
            return findUser(connection, "id = ?", userId).map(StoredUser::toPrincipal);
        } catch (SQLException e) {
            throw databaseFailure("find user by id", e);
        }
    }

    public synchronized List<EnterprisePrincipal> listUsers() {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM enterprise_users ORDER BY LOWER(username)")) {
            List<EnterprisePrincipal> users = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    users.add(requireUser(connection, result.getString("id")).toPrincipal());
                }
            }
            return users;
        } catch (SQLException e) {
            throw databaseFailure("list users", e);
        }
    }

    public synchronized EnterprisePrincipal createUser(
            String username,
            String displayName,
            String passwordHash,
            boolean enabled,
            Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                requireRoles(connection, roles);
                String userId = UUID.randomUUID().toString();
                insertUser(connection, userId, username, displayName, passwordHash, enabled);
                for (String role : roles) {
                    insertUserRole(connection, userId, role);
                }
                connection.commit();
                return requireUser(connection, userId).toPrincipal();
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new IllegalArgumentException("Username already exists");
            }
            throw databaseFailure("create user", e);
        }
    }

    public synchronized EnterprisePrincipal updateUser(
            String userId, String displayName, Boolean enabled, String passwordHash) {
        try (Connection connection = open()) {
            StoredUser existing = requireUser(connection, userId);
            if (Boolean.FALSE.equals(enabled) && existing.roles().contains("SYSTEM_ADMIN")
                    && countEnabledSystemAdmins(connection) <= 1) {
                throw new IllegalArgumentException("The last enabled system administrator cannot be disabled");
            }
            String sql = "UPDATE enterprise_users SET display_name = ?, enabled = ?, password_hash = ?, "
                    + "updated_at = ? WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, displayName == null ? existing.displayName() : displayName.trim());
                statement.setInt(2, enabled == null ? (existing.enabled() ? 1 : 0) : (enabled ? 1 : 0));
                statement.setString(3, passwordHash == null ? existing.passwordHash() : passwordHash);
                statement.setString(4, Instant.now().toString());
                statement.setString(5, userId);
                statement.executeUpdate();
            }
            if (Boolean.FALSE.equals(enabled)) {
                revokeAllSessions(connection, userId);
            }
            return requireUser(connection, userId).toPrincipal();
        } catch (SQLException e) {
            throw databaseFailure("update user", e);
        }
    }

    public synchronized EnterprisePrincipal replaceUserRoles(String userId, Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                StoredUser existing = requireUser(connection, userId);
                requireRoles(connection, roles);
                if (existing.roles().contains("SYSTEM_ADMIN") && !roles.contains("SYSTEM_ADMIN")
                        && countEnabledSystemAdmins(connection) <= 1) {
                    throw new IllegalArgumentException("The last enabled system administrator must keep that role");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM enterprise_user_roles WHERE user_id = ?")) {
                    statement.setString(1, userId);
                    statement.executeUpdate();
                }
                for (String role : roles) {
                    insertUserRole(connection, userId, role);
                }
                connection.commit();
                return requireUser(connection, userId).toPrincipal();
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("replace user roles", e);
        }
    }

    public synchronized EnterprisePrincipal updateOwnProfile(
            String userId, String displayName, String passwordHash) {
        return updateUser(userId, displayName, null, passwordHash);
    }

    /**
     * Deletes an enterprise user and transfers project ownership to the administrator performing
     * the operation. User roles, sessions, memberships and personal Agent bindings are removed by
     * their database foreign keys in the same transaction.
     */
    public synchronized void deleteUser(String userId, String successorUserId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User id is required");
        }
        if (successorUserId == null || successorUserId.isBlank()) {
            throw new IllegalArgumentException("A successor user is required");
        }
        if (userId.equals(successorUserId)) {
            throw new IllegalArgumentException("Users cannot delete themselves");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                StoredUser target = requireUser(connection, userId);
                requireUser(connection, successorUserId);
                if (target.roles().contains("SYSTEM_ADMIN")
                        && target.enabled()
                        && countEnabledSystemAdmins(connection) <= 1) {
                    throw new IllegalArgumentException(
                            "The last enabled system administrator cannot be deleted");
                }
                List<String> ownedProjects = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT project_id FROM enterprise_project_members "
                                + "WHERE user_id = ? AND project_role = 'OWNER'")) {
                    statement.setString(1, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            ownedProjects.add(result.getString("project_id"));
                        }
                    }
                }
                for (String projectId : ownedProjects) {
                    upsertProjectMember(connection, projectId, successorUserId, "OWNER");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE enterprise_project_spaces SET created_by = ?, updated_at = ? "
                                + "WHERE created_by = ?")) {
                    statement.setString(1, successorUserId);
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, userId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM enterprise_users WHERE id = ?")) {
                    statement.setString(1, userId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("User not found: " + userId);
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("delete user", e);
        }
    }

    public synchronized void createSession(String tokenHash, String userId, Instant expiresAt) {
        String sql = "INSERT INTO enterprise_auth_sessions "
                + "(id, user_id, token_hash, issued_at, expires_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, userId);
            statement.setString(3, tokenHash);
            statement.setString(4, Instant.now().toString());
            statement.setString(5, expiresAt.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw databaseFailure("create session", e);
        }
    }

    public synchronized Optional<EnterprisePrincipal> findPrincipalByTokenHash(String tokenHash) {
        String sql = "SELECT user_id FROM enterprise_auth_sessions WHERE token_hash = ? "
                + "AND revoked_at IS NULL AND expires_at > ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            statement.setString(2, Instant.now().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                StoredUser user = requireUser(connection, result.getString("user_id"));
                return user.enabled() ? Optional.of(user.toPrincipal()) : Optional.empty();
            }
        } catch (SQLException e) {
            throw databaseFailure("authenticate session", e);
        }
    }

    public synchronized void revokeSession(String tokenHash) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE enterprise_auth_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, tokenHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw databaseFailure("revoke session", e);
        }
    }

    public synchronized List<RoleRecord> listRoles() {
        String sql = "SELECT code, name, description, system_role FROM enterprise_roles ORDER BY system_role DESC, code";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            List<RoleRecord> roles = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String code = result.getString("code");
                    roles.add(new RoleRecord(
                            code,
                            result.getString("name"),
                            result.getString("description"),
                            result.getInt("system_role") == 1,
                            permissionsForRole(connection, code)
                    ));
                }
            }
            return roles;
        } catch (SQLException e) {
            throw databaseFailure("list roles", e);
        }
    }

    public synchronized RoleRecord saveCustomRole(
            String code, String name, String description, Set<String> permissions) {
        String normalized = normalizeRoleCode(code);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name is required");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                RoleRecord current = findRole(connection, normalized).orElse(null);
                if (current != null && current.systemRole()) {
                    throw new IllegalArgumentException("Built-in roles cannot be modified");
                }
                requirePermissions(connection, permissions);
                String sql = "INSERT INTO enterprise_roles (code, name, description, system_role) VALUES (?, ?, ?, 0) "
                        + "ON CONFLICT(code) DO UPDATE SET name = excluded.name, description = excluded.description";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, normalized);
                    statement.setString(2, name.trim());
                    statement.setString(3, value(description));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM enterprise_role_permissions WHERE role_code = ?")) {
                    statement.setString(1, normalized);
                    statement.executeUpdate();
                }
                for (String permission : permissions == null ? Set.<String>of() : permissions) {
                    insertRolePermission(connection, normalized, permission);
                }
                connection.commit();
                return findRole(connection, normalized).orElseThrow();
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw databaseFailure("save custom role", e);
        }
    }

    public synchronized List<String> listPermissions() {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT code FROM enterprise_permissions ORDER BY code")) {
            List<String> permissions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    permissions.add(result.getString("code"));
                }
            }
            return permissions;
        } catch (SQLException e) {
            throw databaseFailure("list permissions", e);
        }
    }

    public synchronized List<ProjectSpaceRecord> listProjects(EnterprisePrincipal principal) {
        boolean all = principal.hasPermission("projects:manage");
        String sql = all
                ? "SELECT p.*, NULL AS member_role FROM enterprise_project_spaces p ORDER BY LOWER(p.name)"
                : "SELECT p.*, m.project_role AS member_role FROM enterprise_project_spaces p "
                + "JOIN enterprise_project_members m ON m.project_id = p.id "
                + "WHERE m.user_id = ? ORDER BY LOWER(p.name)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!all) {
                statement.setString(1, principal.id());
            }
            List<ProjectSpaceRecord> projects = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    projects.add(projectFrom(result));
                }
            }
            return projects;
        } catch (SQLException e) {
            throw databaseFailure("list project spaces", e);
        }
    }

    public synchronized Optional<ProjectSpaceRecord> findProject(String projectId, EnterprisePrincipal principal) {
        String sql = "SELECT p.*, m.project_role AS member_role FROM enterprise_project_spaces p "
                + "LEFT JOIN enterprise_project_members m ON m.project_id = p.id AND m.user_id = ? WHERE p.id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal.id());
            statement.setString(2, projectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                ProjectSpaceRecord project = projectFrom(result);
                if (!principal.hasPermission("projects:manage") && project.memberRole() == null) {
                    return Optional.empty();
                }
                return Optional.of(project);
            }
        } catch (SQLException e) {
            throw databaseFailure("find project space", e);
        }
    }

    public synchronized ProjectSpaceRecord createProject(
            String code, String name, String description, String createdBy) {
        String normalizedCode = normalizeProjectCode(code);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project space name is required");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String id = UUID.randomUUID().toString();
                String now = Instant.now().toString();
                String sql = "INSERT INTO enterprise_project_spaces "
                        + "(id, code, name, description, enabled, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 1, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, id);
                    statement.setString(2, normalizedCode);
                    statement.setString(3, name.trim());
                    statement.setString(4, value(description));
                    statement.setString(5, createdBy);
                    statement.setString(6, now);
                    statement.setString(7, now);
                    statement.executeUpdate();
                }
                upsertProjectMember(connection, id, createdBy, "OWNER");
                connection.commit();
                return findProjectUnrestricted(connection, id);
            } catch (RuntimeException | SQLException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new IllegalArgumentException("Project space code already exists");
            }
            throw databaseFailure("create project space", e);
        }
    }

    public synchronized ProjectSpaceRecord updateProject(
            String projectId, String name, String description, Boolean enabled) {
        try (Connection connection = open()) {
            ProjectSpaceRecord existing = findProjectUnrestricted(connection, projectId);
            String sql = "UPDATE enterprise_project_spaces SET name = ?, description = ?, enabled = ?, "
                    + "updated_at = ? WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name == null ? existing.name() : requireText(name, "Project space name"));
                statement.setString(2, description == null ? existing.description() : description.trim());
                statement.setInt(3, enabled == null ? (existing.enabled() ? 1 : 0) : (enabled ? 1 : 0));
                statement.setString(4, Instant.now().toString());
                statement.setString(5, projectId);
                statement.executeUpdate();
            }
            return findProjectUnrestricted(connection, projectId);
        } catch (SQLException e) {
            throw databaseFailure("update project space", e);
        }
    }

    public synchronized List<ProjectMemberRecord> listProjectMembers(String projectId) {
        String sql = "SELECT m.project_id, m.user_id, u.username, u.display_name, m.project_role, m.created_at "
                + "FROM enterprise_project_members m JOIN enterprise_users u ON u.id = m.user_id "
                + "WHERE m.project_id = ? ORDER BY LOWER(u.username)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            requireProject(connection, projectId);
            statement.setString(1, projectId);
            List<ProjectMemberRecord> members = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    members.add(new ProjectMemberRecord(
                            result.getString("project_id"),
                            result.getString("user_id"),
                            result.getString("username"),
                            result.getString("display_name"),
                            result.getString("project_role"),
                            result.getString("created_at")
                    ));
                }
            }
            return members;
        } catch (SQLException e) {
            throw databaseFailure("list project members", e);
        }
    }

    public synchronized ProjectMemberRecord putProjectMember(
            String projectId, String userId, String projectRole) {
        String normalizedRole = normalizeProjectRole(projectRole);
        try (Connection connection = open()) {
            requireProject(connection, projectId);
            requireUser(connection, userId);
            upsertProjectMember(connection, projectId, userId, normalizedRole);
            return listProjectMembers(projectId).stream()
                    .filter(member -> member.userId().equals(userId))
                    .findFirst()
                    .orElseThrow();
        } catch (SQLException e) {
            throw databaseFailure("save project member", e);
        }
    }

    public synchronized void deleteProjectMember(String projectId, String userId) {
        try (Connection connection = open()) {
            String currentRole = projectMemberRole(connection, projectId, userId);
            if (currentRole == null) {
                return;
            }
            if ("OWNER".equals(currentRole) && countProjectOwners(connection, projectId) <= 1) {
                throw new IllegalArgumentException("The project space must keep at least one owner");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM enterprise_project_members WHERE project_id = ? AND user_id = ?")) {
                statement.setString(1, projectId);
                statement.setString(2, userId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw databaseFailure("delete project member", e);
        }
    }

    public synchronized boolean canManageProject(String projectId, EnterprisePrincipal principal) {
        if (principal.hasPermission("projects:manage")) {
            return true;
        }
        try (Connection connection = open()) {
            String role = projectMemberRole(connection, projectId, principal.id());
            return "OWNER".equals(role) || "MANAGER".equals(role);
        } catch (SQLException e) {
            throw databaseFailure("authorize project space", e);
        }
    }

    public synchronized Optional<AgentBindingRecord> findPersonalAgentBinding(String userId) {
        String sql = "SELECT agent_id, scope_type, owner_user_id, project_id, created_at "
                + "FROM enterprise_agent_bindings WHERE scope_type = 'PERSONAL' AND owner_user_id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(agentBindingFrom(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw databaseFailure("find personal agent binding", e);
        }
    }

    public synchronized Optional<AgentBindingRecord> findAgentBinding(String agentId) {
        String sql = "SELECT agent_id, scope_type, owner_user_id, project_id, created_at "
                + "FROM enterprise_agent_bindings WHERE agent_id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(agentBindingFrom(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw databaseFailure("find agent binding", e);
        }
    }

    public synchronized AgentBindingRecord bindPersonalAgent(String agentId, String userId) {
        String sql = "INSERT INTO enterprise_agent_bindings "
                + "(agent_id, scope_type, owner_user_id, project_id, created_at) "
                + "VALUES (?, 'PERSONAL', ?, NULL, ?) "
                + "ON CONFLICT(agent_id) DO UPDATE SET scope_type = excluded.scope_type, "
                + "owner_user_id = excluded.owner_user_id, project_id = NULL";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentId);
            statement.setString(2, userId);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
            return findAgentBinding(agentId).orElseThrow();
        } catch (SQLException e) {
            throw databaseFailure("bind personal agent", e);
        }
    }

    public synchronized boolean isProjectMember(String projectId, String userId) {
        if (projectId == null || projectId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        String sql = "SELECT 1 FROM enterprise_project_members WHERE project_id = ? AND user_id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw databaseFailure("check project membership", e);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_users (
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        display_name TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_roles (
                        code TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        system_role INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_permissions (
                        code TEXT PRIMARY KEY,
                        description TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_role_permissions (
                        role_code TEXT NOT NULL,
                        permission_code TEXT NOT NULL,
                        PRIMARY KEY (role_code, permission_code),
                        FOREIGN KEY (role_code) REFERENCES enterprise_roles(code) ON DELETE CASCADE,
                        FOREIGN KEY (permission_code) REFERENCES enterprise_permissions(code) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_user_roles (
                        user_id TEXT NOT NULL,
                        role_code TEXT NOT NULL,
                        PRIMARY KEY (user_id, role_code),
                        FOREIGN KEY (user_id) REFERENCES enterprise_users(id) ON DELETE CASCADE,
                        FOREIGN KEY (role_code) REFERENCES enterprise_roles(code) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_auth_sessions (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        token_hash TEXT NOT NULL UNIQUE,
                        issued_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        revoked_at TEXT,
                        FOREIGN KEY (user_id) REFERENCES enterprise_users(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auth_session_user "
                    + "ON enterprise_auth_sessions(user_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auth_session_expiry "
                    + "ON enterprise_auth_sessions(expires_at)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_project_spaces (
                        id TEXT PRIMARY KEY,
                        code TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        created_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (created_by) REFERENCES enterprise_users(id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_project_members (
                        project_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        project_role TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (project_id, user_id),
                        FOREIGN KEY (project_id) REFERENCES enterprise_project_spaces(id) ON DELETE CASCADE,
                        FOREIGN KEY (user_id) REFERENCES enterprise_users(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enterprise_agent_bindings (
                        agent_id TEXT PRIMARY KEY,
                        scope_type TEXT NOT NULL,
                        owner_user_id TEXT,
                        project_id TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (owner_user_id) REFERENCES enterprise_users(id) ON DELETE CASCADE,
                        FOREIGN KEY (project_id) REFERENCES enterprise_project_spaces(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_personal_agent_owner "
                    + "ON enterprise_agent_bindings(owner_user_id) WHERE scope_type = 'PERSONAL'");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_binding_project "
                    + "ON enterprise_agent_bindings(project_id)");
            seedPermissions(connection);
            seedRoles(connection);
            log.info("Enterprise identity database initialized at {}", jdbcUrl.substring("jdbc:sqlite:".length()));
        } catch (SQLException e) {
            throw databaseFailure("initialize schema", e);
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

    private void seedPermissions(Connection connection) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("users:read", "Read enterprise users");
        values.put("users:manage", "Create and manage enterprise users");
        values.put("roles:read", "Read roles and permissions");
        values.put("roles:manage", "Create and manage custom roles");
        values.put("projects:read", "Read assigned project spaces");
        values.put("projects:manage", "Create and manage every project space");
        values.put("agents:use", "Use published AI services and agents");
        values.put("agents:manage", "Configure and publish AI services and agents");
        values.put("approvals:decide", "Approve or reject high-risk operations");
        values.put("audit:read", "Read enterprise audit records");
        values.put("system:manage", "Manage system-wide settings");
        values.put(EnterprisePermissions.DATA_BREAKGLASS,
                "Temporarily access user content through the audited break-glass workflow");
        values.put(EnterprisePermissions.PERSONAL_WORKSPACE, "Manage the user's isolated workspace and memory");
        values.put(EnterprisePermissions.PERSONAL_SKILLS, "Manage skills in the user's isolated workspace");
        values.put(EnterprisePermissions.PERSONAL_CHANNELS, "Manage the user's isolated channel connections");
        values.put(EnterprisePermissions.PERSONAL_AUTOMATIONS, "Manage the user's isolated schedules and heartbeat");
        String sql = "INSERT OR IGNORE INTO enterprise_permissions (code, description) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void seedRoles(Connection connection) throws SQLException {
        insertRole(connection, "SYSTEM_ADMIN", "System administrator", "Full platform administration", true);
        insertRole(connection, "SERVICE_OPERATOR", "AI service operator", "Build and operate AI services", true);
        insertRole(connection, "APPROVER", "Approver", "Approve high-risk operations", true);
        insertRole(connection, "EMPLOYEE", "Employee", "Use assigned enterprise AI services", true);
        insertRole(connection, "AUDITOR", "Auditor", "Read audit and operational records", true);
        insertRole(connection, "ORG_ADMIN", "Organization administrator",
                "Manage enterprise identities, roles and project spaces without host or Agent access", true);

        for (String permission : listPermissionCodes(connection)) {
            if (!EnterprisePermissions.DATA_BREAKGLASS.equals(permission)) {
                insertRolePermission(connection, "SYSTEM_ADMIN", permission);
            }
        }
        insertRolePermission(connection, "SERVICE_OPERATOR", "projects:read");
        insertRolePermission(connection, "SERVICE_OPERATOR", "agents:use");
        insertRolePermission(connection, "SERVICE_OPERATOR", "agents:manage");
        insertRolePermission(connection, "SERVICE_OPERATOR", "approvals:decide");
        insertRolePermission(connection, "APPROVER", "projects:read");
        insertRolePermission(connection, "APPROVER", "agents:use");
        insertRolePermission(connection, "APPROVER", "approvals:decide");
        insertRolePermission(connection, "EMPLOYEE", "projects:read");
        insertRolePermission(connection, "EMPLOYEE", "agents:use");
        insertRolePermission(connection, "AUDITOR", "projects:read");
        insertRolePermission(connection, "AUDITOR", "audit:read");

        for (String permission : Set.of(
                "users:read", "users:manage", "roles:read", "roles:manage",
                "projects:read", "projects:manage", "audit:read")) {
            insertRolePermission(connection, "ORG_ADMIN", permission);
        }

        // Existing custom roles that could use an Agent retain the personal capabilities
        // that were implicit before these permissions became independently assignable.
        for (String permission : EnterprisePermissions.PERSONAL_CAPABILITIES) {
            inheritPermission(connection, "agents:use", permission);
        }
    }

    private void inheritPermission(Connection connection, String sourcePermission, String targetPermission)
            throws SQLException {
        String sql = "INSERT OR IGNORE INTO enterprise_role_permissions (role_code, permission_code) "
                + "SELECT role_code, ? FROM enterprise_role_permissions WHERE permission_code = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetPermission);
            statement.setString(2, sourcePermission);
            statement.executeUpdate();
        }
    }

    private void insertRole(
            Connection connection, String code, String name, String description, boolean systemRole) throws SQLException {
        String sql = "INSERT OR IGNORE INTO enterprise_roles (code, name, description, system_role) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, description);
            statement.setInt(4, systemRole ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertRolePermission(Connection connection, String role, String permission) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO enterprise_role_permissions (role_code, permission_code) VALUES (?, ?)")) {
            statement.setString(1, role);
            statement.setString(2, permission);
            statement.executeUpdate();
        }
    }

    private List<String> listPermissionCodes(Connection connection) throws SQLException {
        List<String> permissions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code FROM enterprise_permissions ORDER BY code");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                permissions.add(result.getString("code"));
            }
        }
        return permissions;
    }

    private Optional<StoredUser> findUser(Connection connection, String condition, String parameter) throws SQLException {
        String sql = "SELECT id, username, display_name, password_hash, enabled, created_at, updated_at "
                + "FROM enterprise_users WHERE " + condition;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String id = result.getString("id");
                Set<String> roles = rolesForUser(connection, id);
                return Optional.of(new StoredUser(
                        id,
                        result.getString("username"),
                        result.getString("display_name"),
                        result.getString("password_hash"),
                        result.getInt("enabled") == 1,
                        roles,
                        permissionsForRoles(connection, roles),
                        result.getString("created_at"),
                        result.getString("updated_at")
                ));
            }
        }
    }

    private StoredUser requireUser(Connection connection, String userId) throws SQLException {
        return findUser(connection, "id = ?", userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    private Set<String> rolesForUser(Connection connection, String userId) throws SQLException {
        Set<String> roles = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role_code FROM enterprise_user_roles WHERE user_id = ? ORDER BY role_code")) {
            statement.setString(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    roles.add(result.getString("role_code"));
                }
            }
        }
        return roles;
    }

    private Set<String> permissionsForRoles(Connection connection, Set<String> roles) throws SQLException {
        Set<String> permissions = new LinkedHashSet<>();
        for (String role : roles) {
            permissions.addAll(permissionsForRole(connection, role));
        }
        return permissions;
    }

    private Set<String> permissionsForRole(Connection connection, String role) throws SQLException {
        Set<String> permissions = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT permission_code FROM enterprise_role_permissions WHERE role_code = ? ORDER BY permission_code")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    permissions.add(result.getString("permission_code"));
                }
            }
        }
        return permissions;
    }

    private Optional<RoleRecord> findRole(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code, name, description, system_role FROM enterprise_roles WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RoleRecord(
                        code,
                        result.getString("name"),
                        result.getString("description"),
                        result.getInt("system_role") == 1,
                        permissionsForRole(connection, code)
                ));
            }
        }
    }

    private void requireRoles(Connection connection, Set<String> roles) throws SQLException {
        for (String role : roles) {
            if (findRole(connection, normalizeRoleCode(role)).isEmpty()) {
                throw new IllegalArgumentException("Unknown role: " + role);
            }
        }
    }

    private void requirePermissions(Connection connection, Set<String> permissions) throws SQLException {
        for (String permission : permissions == null ? Set.<String>of() : permissions) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM enterprise_permissions WHERE code = ?")) {
                statement.setString(1, permission);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException("Unknown permission: " + permission);
                    }
                }
            }
        }
    }

    private void insertUser(
            Connection connection,
            String userId,
            String username,
            String displayName,
            String passwordHash,
            boolean enabled) throws SQLException {
        String normalizedUsername = normalizeUsername(username);
        String now = Instant.now().toString();
        String sql = "INSERT INTO enterprise_users "
                + "(id, username, display_name, password_hash, enabled, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, normalizedUsername);
            statement.setString(3, displayName == null || displayName.isBlank() ? normalizedUsername : displayName.trim());
            statement.setString(4, passwordHash);
            statement.setInt(5, enabled ? 1 : 0);
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
        }
    }

    private void insertUserRole(Connection connection, String userId, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO enterprise_user_roles (user_id, role_code) VALUES (?, ?)")) {
            statement.setString(1, userId);
            statement.setString(2, normalizeRoleCode(role));
            statement.executeUpdate();
        }
    }

    private long countEnabledSystemAdmins(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM enterprise_users u JOIN enterprise_user_roles r ON r.user_id = u.id "
                + "WHERE u.enabled = 1 AND r.role_code = 'SYSTEM_ADMIN'";
        return scalarLong(connection, sql);
    }

    private void revokeAllSessions(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE enterprise_auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, userId);
            statement.executeUpdate();
        }
    }

    private ProjectSpaceRecord projectFrom(ResultSet result) throws SQLException {
        return new ProjectSpaceRecord(
                result.getString("id"),
                result.getString("code"),
                result.getString("name"),
                result.getString("description"),
                result.getInt("enabled") == 1,
                result.getString("created_by"),
                result.getString("member_role"),
                result.getString("created_at"),
                result.getString("updated_at")
        );
    }

    private AgentBindingRecord agentBindingFrom(ResultSet result) throws SQLException {
        return new AgentBindingRecord(
                result.getString("agent_id"),
                result.getString("scope_type"),
                result.getString("owner_user_id"),
                result.getString("project_id"),
                result.getString("created_at")
        );
    }

    private ProjectSpaceRecord findProjectUnrestricted(Connection connection, String projectId) throws SQLException {
        String sql = "SELECT p.*, NULL AS member_role FROM enterprise_project_spaces p WHERE p.id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Project space not found: " + projectId);
                }
                return projectFrom(result);
            }
        }
    }

    private void requireProject(Connection connection, String projectId) throws SQLException {
        findProjectUnrestricted(connection, projectId);
    }

    private void upsertProjectMember(
            Connection connection, String projectId, String userId, String projectRole) throws SQLException {
        String sql = "INSERT INTO enterprise_project_members "
                + "(project_id, user_id, project_role, created_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(project_id, user_id) DO UPDATE SET project_role = excluded.project_role";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, userId);
            statement.setString(3, projectRole);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private String projectMemberRole(Connection connection, String projectId, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT project_role FROM enterprise_project_members WHERE project_id = ? AND user_id = ?")) {
            statement.setString(1, projectId);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("project_role") : null;
            }
        }
    }

    private long countProjectOwners(Connection connection, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM enterprise_project_members WHERE project_id = ? AND project_role = 'OWNER'")) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private static String normalizeUsername(String username) {
        String value = requireText(username, "Username").toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException(
                    "Username must be 3-64 characters and contain only letters, numbers, '.', '_' or '-'");
        }
        return value;
    }

    private static String normalizeRoleCode(String code) {
        String value = requireText(code, "Role code").toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Role code must contain 3-64 uppercase letters, numbers or underscores");
        }
        return value;
    }

    private static String normalizeProjectCode(String code) {
        String value = requireText(code, "Project space code").toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{1,62}[a-z0-9]")) {
            throw new IllegalArgumentException("Project space code must be 3-64 lowercase letters, numbers or hyphens");
        }
        return value;
    }

    private static String normalizeProjectRole(String role) {
        String value = requireText(role, "Project member role").toUpperCase(Locale.ROOT);
        if (!PROJECT_ROLES.contains(value)) {
            throw new IllegalArgumentException("Project member role must be OWNER, MANAGER, MEMBER or VIEWER");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static Path resolveDatabasePath(String configuredPath, String homeDir) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return expandHome(configuredPath.trim());
        }
        return expandHome(homeDir == null || homeDir.isBlank() ? "~/.melonAI" : homeDir)
                .resolve("enterprise.db");
    }

    private static Path expandHome(String value) {
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value);
    }

    private static boolean isUniqueViolation(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("unique constraint failed");
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private static IllegalStateException databaseFailure(String operation, SQLException exception) {
        return new IllegalStateException("Failed to " + operation + ": " + exception.getMessage(), exception);
    }

    public record StoredUser(
            String id,
            String username,
            String displayName,
            String passwordHash,
            boolean enabled,
            Set<String> roles,
            Set<String> permissions,
            String createdAt,
            String updatedAt
    ) {
        public EnterprisePrincipal toPrincipal() {
            return new EnterprisePrincipal(
                    id, username, displayName, enabled, roles, permissions, createdAt, updatedAt);
        }
    }

    public record RoleRecord(
            String code, String name, String description, boolean systemRole, Set<String> permissions) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "code", code,
                    "name", name,
                    "description", description,
                    "system_role", systemRole,
                    "permissions", permissions.stream().sorted().toList()
            );
        }
    }

    public record ProjectSpaceRecord(
            String id,
            String code,
            String name,
            String description,
            boolean enabled,
            String createdBy,
            String memberRole,
            String createdAt,
            String updatedAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("code", code);
            result.put("name", name);
            result.put("description", description);
            result.put("enabled", enabled);
            result.put("created_by", createdBy);
            result.put("member_role", memberRole);
            result.put("created_at", createdAt);
            result.put("updated_at", updatedAt);
            return result;
        }
    }

    public record ProjectMemberRecord(
            String projectId,
            String userId,
            String username,
            String displayName,
            String projectRole,
            String createdAt
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "project_id", projectId,
                    "user_id", userId,
                    "username", username,
                    "display_name", displayName,
                    "project_role", projectRole,
                    "created_at", createdAt
            );
        }
    }

    public record AgentBindingRecord(
            String agentId,
            String scopeType,
            String ownerUserId,
            String projectId,
            String createdAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent_id", agentId);
            result.put("scope_type", scopeType);
            result.put("owner_user_id", ownerUserId);
            result.put("project_id", projectId);
            result.put("created_at", createdAt);
            return result;
        }
    }
}
