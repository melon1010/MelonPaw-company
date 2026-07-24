package com.melon.app.runner;

import com.melon.core.agent.AgentScopedStateStore;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamChatSharingTest {

    @TempDir
    Path home;

    @Test
    void projectMembersShareConversationButNotApprovalState() {
        ConfigManager config = config("project-a");
        InMemoryAgentStateStore states = new InMemoryAgentStateStore();
        ChatManager chats = new ChatManager(
                config, null, states, agentId -> "project-a".equals(agentId));

        ChatSpec original = chats.getOrCreateForSession(
                "project-a", "session-1", "user-a", "console", "Shared chat");
        AgentState source = AgentState.builder()
                .sessionId("session-1")
                .userId("user-a")
                .summary("shared summary")
                .context(List.of(new UserMessage("shared message")))
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .build();
        states.save(AgentScopedStateStore.scopedUserId("project-a", "user-a"),
                "session-1", "agent_state", source);

        ChatSpec continued = chats.beginTurn(
                "project-a", "session-1", "user-b", "console", "ignored");
        String turnId = chats.activeTurnId("project-a", continued.getId());
        AgentState handedOff = states.get(
                        AgentScopedStateStore.scopedUserId("project-a", "user-b"),
                        "session-1", "agent_state", AgentState.class)
                .orElseThrow();

        assertEquals(original.getId(), continued.getId());
        assertEquals("shared summary", handedOff.getSummary());
        assertEquals(1, handedOff.getContext().size());
        assertEquals("user-b", handedOff.getUserId());
        assertNotEquals(source.getPermissionContext().getMode(), handedOff.getPermissionContext().getMode());
        assertEquals("user-b", chats.activeUserId("project-a", continued.getId(), "missing"));
        assertEquals(1, chats.list("project-a", "user-a", "console").size());

        chats.saveSessionShadow("project-a", "console", "user-b", "session-1",
                Map.of("context", List.of(Map.of("role", "user", "content", "continued"))));
        assertFalse(chats.loadSessionShadow(
                "project-a", "console", "user-b", "session-1").isEmpty());
        chats.endTurn("project-a", continued.getId(), "user-b", turnId);

        assertEquals(1, chats.list("project-a", null, "console").size());
        assertEquals("user-b", chats.get("project-a", continued.getId())
                .getMeta().get("state_user_id"));
        assertTrue(Files.isRegularFile(chats.sessionFile(
                "project-a", "console", "user-a", "session-1")));
        assertFalse(Files.exists(chats.sessionFile(
                "project-a", "console", "user-b", "session-1")));
    }

    @Test
    void personalConversationsRemainUserIsolated() {
        ConfigManager config = config("personal-a");
        ChatManager chats = new ChatManager(
                config, null, new InMemoryAgentStateStore(), ignored -> false);

        ChatSpec first = chats.getOrCreateForSession(
                "personal-a", "same-session", "user-a", "console", "A");
        ChatSpec second = chats.getOrCreateForSession(
                "personal-a", "same-session", "user-b", "console", "B");

        assertNotEquals(first.getId(), second.getId());
        assertEquals(List.of(first.getId()), chats.list("personal-a", "user-a", "console")
                .stream().map(ChatSpec::getId).toList());
        assertEquals(List.of(second.getId()), chats.list("personal-a", "user-b", "console")
                .stream().map(ChatSpec::getId).toList());
    }

    @Test
    void staleCompletionCannotCloseANewerSharedTurn() {
        ConfigManager config = config("project-a");
        InMemoryAgentStateStore states = new InMemoryAgentStateStore();
        ChatManager chats = new ChatManager(
                config, null, states, agentId -> "project-a".equals(agentId));

        ChatSpec first = chats.beginTurn(
                "project-a", "session-1", "user-a", "console", "Shared chat");
        String firstTurnId = chats.activeTurnId("project-a", first.getId());
        chats.endTurn("project-a", first.getId(), "user-b", firstTurnId);
        assertEquals("running", chats.get("project-a", first.getId()).getStatus());

        chats.endTurn("project-a", first.getId(), "user-a", firstTurnId);
        ChatSpec second = chats.beginExistingTurn("project-a", first.getId(), "user-b");
        String secondTurnId = chats.activeTurnId("project-a", first.getId());
        assertNotEquals(firstTurnId, secondTurnId);

        chats.endTurn("project-a", first.getId(), "user-a", firstTurnId);
        assertEquals("running", chats.get("project-a", first.getId()).getStatus());
        assertEquals("user-b", chats.activeUserId("project-a", first.getId(), "missing"));

        chats.endTurn("project-a", second.getId(), "user-b", secondTurnId);
        assertEquals("idle", chats.get("project-a", first.getId()).getStatus());
    }

    @Test
    void newProcessRecoversPersistedRunningStatusWithoutAcceptingOldCompletion() {
        ConfigManager config = config("project-a");
        InMemoryAgentStateStore states = new InMemoryAgentStateStore();
        ChatManager oldProcess = new ChatManager(
                config, null, states, agentId -> "project-a".equals(agentId));
        ChatSpec first = oldProcess.beginTurn(
                "project-a", "session-1", "user-a", "console", "Shared chat");
        String oldTurnId = oldProcess.activeTurnId("project-a", first.getId());

        ChatManager restarted = new ChatManager(
                config, null, states, agentId -> "project-a".equals(agentId));
        ChatSpec recovered = restarted.beginExistingTurn("project-a", first.getId(), "user-b");
        String recoveredTurnId = restarted.activeTurnId("project-a", recovered.getId());
        assertNotEquals(oldTurnId, recoveredTurnId);

        oldProcess.endTurn("project-a", first.getId(), "user-a", oldTurnId);
        assertEquals("running", restarted.get("project-a", recovered.getId()).getStatus());
        assertEquals(recoveredTurnId, restarted.activeTurnId("project-a", recovered.getId()));

        restarted.endTurn("project-a", recovered.getId(), "user-b", recoveredTurnId);
        assertEquals("idle", restarted.get("project-a", recovered.getId()).getStatus());
    }

    private ConfigManager config(String agentId) {
        ConfigManager manager = new ConfigManager();
        manager.setHomeDir(home.toString());
        AgentConfig agent = new AgentConfig();
        agent.setWorkspaceDir(home.resolve("workspaces").resolve(agentId).toString());
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put(agentId, agent);
        manager.getConfig().setAgents(agents);
        return manager;
    }
}
