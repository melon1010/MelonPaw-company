package com.melon.core.agent;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Adds the managed agent id to AgentScope's user/session state namespace. */
public final class AgentScopedStateStore implements AgentStateStore {

    private final AgentStateStore delegate;
    private final String agentId;

    public AgentScopedStateStore(AgentStateStore delegate, String agentId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.agentId = requireAgentId(agentId);
    }

    public static String scopedUserId(String agentId, String userId) {
        String id = requireAgentId(agentId);
        String user = userId == null || userId.isBlank() ? "__anon__" : userId;
        return "agent-" + id.length() + "-" + id + "-" + user;
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(scopedUserId(agentId, userId), sessionId, key, state);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        delegate.save(scopedUserId(agentId, userId), sessionId, key, states);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(scopedUserId(agentId, userId), sessionId, key, type);
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(scopedUserId(agentId, userId), sessionId, key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(scopedUserId(agentId, userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(scopedUserId(agentId, userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(scopedUserId(agentId, userId), sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(scopedUserId(agentId, userId));
    }

    private static String requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        return agentId.trim();
    }
}
