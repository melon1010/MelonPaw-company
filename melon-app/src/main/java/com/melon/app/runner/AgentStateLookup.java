package com.melon.app.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.agent.AgentScopedStateStore;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Reads current agent-scoped state and the pre-migration state layouts. */
public final class AgentStateLookup {

    private static final String AGENT_STATE_KEY = "agent_state";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AgentStateStore stateStore;

    public AgentStateLookup(AgentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public Optional<AgentState> find(String agentId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();

        Optional<AgentState> current = get(AgentScopedStateStore.scopedUserId(agentId, userId), sessionId);
        if (current.isPresent()) return current;

        // Read-only fallbacks keep existing histories visible after introducing agent namespaces.
        Optional<AgentState> legacy = get(userId, sessionId);
        if (legacy.isPresent()) return legacy;
        legacy = get(agentId, sessionId);
        if (legacy.isPresent()) return legacy;
        return get(null, sessionId);
    }

    public Map<String, Object> findAsMap(String agentId, String userId, String sessionId) {
        return find(agentId, userId, sessionId)
                .map(state -> JsonUtils.getMapper().convertValue(state, MAP_TYPE))
                .map(Map.class::cast)
                .orElseGet(Map::of);
    }

    private Optional<AgentState> get(String userId, String sessionId) {
        return stateStore.get(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
    }
}
