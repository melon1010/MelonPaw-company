package com.melon.app.auth;

import org.springframework.stereotype.Component;

/** Defines the data-sharing boundary for managed Agents. */
@Component
public class AgentSharingPolicy {

    private final EnterpriseIdentityStore identityStore;

    public AgentSharingPolicy(EnterpriseIdentityStore identityStore) {
        this.identityStore = identityStore;
    }

    /** Project Agents share workspace data across authorized project members. */
    public boolean sharesAcrossUsers(String agentId) {
        if (agentId == null || agentId.isBlank()) return false;
        return identityStore.findAgentBinding(agentId.trim())
                .map(binding -> "PROJECT".equals(binding.scopeType()))
                .orElse(false);
    }
}
