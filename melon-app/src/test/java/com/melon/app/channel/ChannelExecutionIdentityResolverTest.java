package com.melon.app.channel;

import com.melon.app.auth.EnterpriseIdentityStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChannelExecutionIdentityResolverTest {

    @Test
    void resolvesPersonalAgentOwnerInsteadOfExternalChannelUser() {
        EnterpriseIdentityStore store = mock(EnterpriseIdentityStore.class);
        when(store.findAgentBinding("personal-agent")).thenReturn(Optional.of(
                new EnterpriseIdentityStore.AgentBindingRecord(
                        "personal-agent", "PERSONAL", "owner-user", null, "2026-01-01T00:00:00Z")));

        ChannelExecutionIdentityResolver resolver = new ChannelExecutionIdentityResolver(store);

        assertEquals("owner-user", resolver.resolve("personal-agent", ""));
    }

    @Test
    void keepsTrustedInternalExecutionIdentity() {
        EnterpriseIdentityStore store = mock(EnterpriseIdentityStore.class);
        ChannelExecutionIdentityResolver resolver = new ChannelExecutionIdentityResolver(store);

        assertEquals("trusted-user", resolver.resolve("agent", " trusted-user "));
        verifyNoInteractions(store);
    }

    @Test
    void choosesStableAuthorizedProjectMember() {
        EnterpriseIdentityStore store = mock(EnterpriseIdentityStore.class);
        when(store.findAgentBinding("project-agent")).thenReturn(Optional.of(
                new EnterpriseIdentityStore.AgentBindingRecord(
                        "project-agent", "PROJECT", null, "project-1", "2026-01-01T00:00:00Z")));
        when(store.listProjectMembers("project-1")).thenReturn(List.of(
                member("viewer", "VIEWER", "2026-01-01T00:00:00Z"),
                member("manager", "MANAGER", "2026-01-02T00:00:00Z"),
                member("owner", "OWNER", "2026-01-03T00:00:00Z")));

        ChannelExecutionIdentityResolver resolver = new ChannelExecutionIdentityResolver(store);

        assertEquals("owner", resolver.resolve("project-agent", ""));
    }

    private EnterpriseIdentityStore.ProjectMemberRecord member(
            String userId, String role, String createdAt) {
        return new EnterpriseIdentityStore.ProjectMemberRecord(
                "project-1", userId, userId, userId, role, createdAt);
    }
}
