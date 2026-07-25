package com.melon.app.auth;

import java.util.Set;

/** Stable permission codes used by the enterprise authorization boundary. */
public final class EnterprisePermissions {

    public static final String USERS_MANAGE = "users:manage";
    public static final String ROLES_MANAGE = "roles:manage";
    public static final String PROJECTS_MANAGE = "projects:manage";
    public static final String MODELS_MANAGE = "models:manage";
    public static final String SKILL_POOL_MANAGE = "skill-pool:manage";
    public static final String ENVIRONMENT_MANAGE = "environment:manage";
    public static final String SECURITY_MANAGE = "security:manage";
    public static final String TOKEN_USAGE_READ = "token-usage:read";
    public static final String AUDIT_READ = "audit:read";
    public static final String BACKUPS_MANAGE = "backups:manage";
    public static final String QUOTAS_MANAGE = "quotas:manage";
    public static final String TRANSCRIPTION_MANAGE = "transcription:manage";
    public static final String DEBUG_MANAGE = "debug:manage";
    public static final String PLUGINS_MANAGE = "plugins:manage";

    public static final String AGENTS_USE = "agents:use";
    public static final String AGENTS_MANAGE = "agents:manage";
    public static final String APPROVALS_DECIDE = "approvals:decide";
    public static final String SYSTEM_MANAGE = "system:manage";
    /** Explicit, audited access to user content; not implied by administration. */
    public static final String DATA_BREAKGLASS = "data:breakglass";

    public static final String PERSONAL_WORKSPACE = "workspace:personal";
    public static final String PERSONAL_SKILLS = "skills:personal";
    public static final String PERSONAL_CHANNELS = "channels:personal";
    public static final String PERSONAL_AUTOMATIONS = "automations:personal";
    public static final String PERSONAL_MCP = "mcp:personal";

    public static final Set<String> PERSONAL_CAPABILITIES = Set.of(
            PERSONAL_WORKSPACE,
            PERSONAL_SKILLS,
            PERSONAL_CHANNELS,
            PERSONAL_AUTOMATIONS,
            PERSONAL_MCP
    );

    /** Permissions administrators may assign to custom roles from the role editor. */
    public static final Set<String> ASSIGNABLE_ROLE_PERMISSIONS = Set.of(
            USERS_MANAGE,
            ROLES_MANAGE,
            PROJECTS_MANAGE,
            MODELS_MANAGE,
            SKILL_POOL_MANAGE,
            ENVIRONMENT_MANAGE,
            SECURITY_MANAGE,
            TOKEN_USAGE_READ,
            AUDIT_READ,
            BACKUPS_MANAGE,
            QUOTAS_MANAGE,
            TRANSCRIPTION_MANAGE,
            DEBUG_MANAGE,
            PLUGINS_MANAGE
    );

    /** Internal capabilities carried by the built-in USER role. */
    public static final Set<String> USER_BASE_PERMISSIONS = Set.of(
            "projects:read",
            AGENTS_USE,
            PERSONAL_WORKSPACE,
            PERSONAL_SKILLS,
            PERSONAL_CHANNELS,
            PERSONAL_AUTOMATIONS,
            PERSONAL_MCP
    );

    private EnterprisePermissions() {
    }
}
