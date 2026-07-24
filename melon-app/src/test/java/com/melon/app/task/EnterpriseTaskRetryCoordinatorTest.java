package com.melon.app.task;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseTaskRetryCoordinatorTest {

    @Test
    void standaloneTaskPersistsReplayInputAndRunsThroughAgentRunner() {
        EnterpriseTaskService tasks = mock(EnterpriseTaskService.class);
        AgentRunner runner = mock(AgentRunner.class);
        ChatManager chats = mock(ChatManager.class);
        EnterpriseTaskService.TaskRun run = new EnterpriseTaskService.TaskRun("task-1", "run-1", true);
        when(tasks.startForAgent(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString())).thenReturn(run);
        when(runner.stream(anyString(), anyList(), anyString(), anyString(), anyMap()))
                .thenReturn(Flux.empty());
        EnterpriseTaskRetryCoordinator coordinator =
                new EnterpriseTaskRetryCoordinator(tasks, runner, chats);

        EnterpriseTaskService.TaskRun submitted = coordinator.submit(
                "user-1", "agent-1", "Standalone", "Create the report", "agent", "request-1");

        assertEquals(run, submitted);
        ArgumentCaptor<EnterpriseTaskService.RetryIntent> intent =
                ArgumentCaptor.forClass(EnterpriseTaskService.RetryIntent.class);
        verify(tasks).setRetryIntent(any(), intent.capture());
        assertEquals("STREAM", intent.getValue().mode());
        assertEquals("Create the report", intent.getValue().message());
        verify(runner).stream(anyString(), anyList(), anyString(), anyString(), anyMap());
        verify(tasks, timeout(1_000)).succeed(run, "");
    }

    @Test
    void sharedConversationRetryUsesTheCurrentActorIdentity() {
        EnterpriseTaskService tasks = mock(EnterpriseTaskService.class);
        AgentRunner runner = mock(AgentRunner.class);
        ChatManager chats = mock(ChatManager.class);
        EnterpriseTaskService.TaskRun run = new EnterpriseTaskService.TaskRun("task-1", "run-2", true);
        EnterpriseTaskService.RetryIntent intent = new EnterpriseTaskService.RetryIntent(
                "STREAM", "Continue the shared work", Map.of("channel", "console"));
        when(tasks.beginRetry("task-1", "user-b"))
                .thenReturn(new EnterpriseTaskService.RetryStart(run, intent));
        when(tasks.get("task-1")).thenReturn(Map.of(
                "agent_id", "project-a",
                "owner_user_id", "user-a",
                "session_id", "session-1",
                "chat_id", "chat-1"));
        when(chats.isSharedAgent("project-a")).thenReturn(true);
        when(chats.activeTurnId("project-a", "chat-1")).thenReturn("turn-1");
        when(runner.stream(anyString(), anyList(), anyString(), anyString(), anyMap()))
                .thenReturn(Flux.empty());
        EnterpriseTaskRetryCoordinator coordinator =
                new EnterpriseTaskRetryCoordinator(tasks, runner, chats);

        coordinator.retry("task-1", "user-b");

        verify(chats).beginExistingTurn("project-a", "chat-1", "user-b");
        verify(runner).stream(eq("project-a"), anyList(), eq("user-b"),
                eq("session-1"), anyMap());
        verify(chats, timeout(1_000)).endTurn(
                "project-a", "chat-1", "user-b", "turn-1");
    }
}
