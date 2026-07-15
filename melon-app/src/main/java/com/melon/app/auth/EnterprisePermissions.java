package com.melon.app.auth;

import java.util.Set;

/** Stable permission codes used by the enterprise authorization boundary. */
public final class EnterprisePermissions {

    public static final String AGENTS_USE = "agents:use";
    public static final String AGENTS_MANAGE = "agents:manage";
    public static final String APPROVALS_DECIDE = "approvals:decide";
    public static final String AUDIT_READ = "audit:read";
    public static final String SYSTEM_MANAGE = "system:manage";
    /** Explicit, audited access to user content; not implied by administration. */
    public static final String DATA_BREAKGLASS = "data:breakglass";

    public static final String PERSONAL_WORKSPACE = "workspace:personal";
    public static final String PERSONAL_SKILLS = "skills:personal";
    public static final String PERSONAL_CHANNELS = "channels:personal";
    public static final String PERSONAL_AUTOMATIONS = "automations:personal";

    public static final Set<String> PERSONAL_CAPABILITIES = Set.of(
            PERSONAL_WORKSPACE,
            PERSONAL_SKILLS,
            PERSONAL_CHANNELS,
            PERSONAL_AUTOMATIONS
    );

    private EnterprisePermissions() {
    }
}
