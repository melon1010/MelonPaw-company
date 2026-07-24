package com.melon.app.channel;

import com.melon.app.auth.EnterpriseIdentityStore;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Locale;

/** Resolves the enterprise user that owns an execution triggered by an external channel. */
@Component
public class ChannelExecutionIdentityResolver {

    private final EnterpriseIdentityStore identityStore;

    public ChannelExecutionIdentityResolver(EnterpriseIdentityStore identityStore) {
        this.identityStore = identityStore;
    }

    public String resolve(String agentId, String trustedExecutionUserId) {
        String trusted = text(trustedExecutionUserId);
        if (!trusted.isBlank()) return trusted;

        EnterpriseIdentityStore.AgentBindingRecord binding = identityStore.findAgentBinding(agentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Channel execution requires an enterprise Agent binding: " + agentId));
        if ("PERSONAL".equalsIgnoreCase(binding.scopeType())) {
            return required(binding.ownerUserId(), "Personal Agent owner is missing");
        }
        if ("PROJECT".equalsIgnoreCase(binding.scopeType())) {
            String projectId = required(binding.projectId(), "Project Agent project is missing");
            return identityStore.listProjectMembers(projectId).stream()
                    .filter(member -> roleRank(member.projectRole()) < 3)
                    .min(Comparator
                            .comparingInt((EnterpriseIdentityStore.ProjectMemberRecord member) ->
                                    roleRank(member.projectRole()))
                            .thenComparing(EnterpriseIdentityStore.ProjectMemberRecord::createdAt)
                            .thenComparing(EnterpriseIdentityStore.ProjectMemberRecord::userId))
                    .map(EnterpriseIdentityStore.ProjectMemberRecord::userId)
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalStateException(
                            "Project Agent has no member allowed to execute channel messages: " + agentId));
        }
        throw new IllegalStateException("Unsupported Agent binding scope for channel execution: "
                + binding.scopeType());
    }

    private static int roleRank(String role) {
        return switch (text(role).toUpperCase(Locale.ROOT)) {
            case "OWNER" -> 0;
            case "MANAGER" -> 1;
            case "MEMBER" -> 2;
            default -> 3;
        };
    }

    private static String required(String value, String message) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalStateException(message);
        return normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
