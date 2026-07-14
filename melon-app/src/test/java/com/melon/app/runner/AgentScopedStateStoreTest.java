package com.melon.app.runner;

import com.melon.core.agent.AgentScopedStateStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScopedStateStoreTest {

    @Test
    void isolatesSameUserAndSessionAcrossAgents() {
        InMemoryAgentStateStore delegate = new InMemoryAgentStateStore();
        AgentScopedStateStore first = new AgentScopedStateStore(delegate, "agent-a");
        AgentScopedStateStore second = new AgentScopedStateStore(delegate, "agent-b");

        first.save("user-1", "session-1", "agent_state", state("user-1", "session-1", "first"));
        second.save("user-1", "session-1", "agent_state", state("user-1", "session-1", "second"));

        assertEquals("first", first.get("user-1", "session-1", "agent_state", AgentState.class)
                .orElseThrow().getSummary());
        assertEquals("second", second.get("user-1", "session-1", "agent_state", AgentState.class)
                .orElseThrow().getSummary());
        assertNotEquals(
                AgentScopedStateStore.scopedUserId("agent-a", "user-1"),
                AgentScopedStateStore.scopedUserId("agent-b", "user-1"));
    }

    @Test
    void readsLegacyStateWithoutWritingItIntoAnotherAgentNamespace() {
        InMemoryAgentStateStore delegate = new InMemoryAgentStateStore();
        delegate.save("user-1", "legacy-session", "agent_state",
                state("user-1", "legacy-session", "legacy"));

        AgentStateLookup lookup = new AgentStateLookup(delegate);
        assertEquals("legacy", lookup.find("agent-a", "user-1", "legacy-session")
                .orElseThrow().getSummary());
        assertTrue(delegate.get(
                AgentScopedStateStore.scopedUserId("agent-a", "user-1"),
                "legacy-session", "agent_state", AgentState.class).isEmpty());
    }

    private AgentState state(String userId, String sessionId, String summary) {
        return AgentState.builder()
                .userId(userId)
                .sessionId(sessionId)
                .summary(summary)
                .build();
    }
}
