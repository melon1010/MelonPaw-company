package com.melon.app.task;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
}
