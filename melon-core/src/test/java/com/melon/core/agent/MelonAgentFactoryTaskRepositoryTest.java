package com.melon.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MelonAgentFactoryTaskRepositoryTest {

    @TempDir
    Path workspace;

    @Test
    void backgroundTaskMetadataRemainsAvailableWithoutAnActiveSandbox() throws Exception {
        WorkspaceTaskRepository repository =
                MelonAgentFactory.backgroundTaskRepository("personal-test", workspace);
        RuntimeContext context = RuntimeContext.builder()
                .userId("user-test")
                .sessionId("session-test")
                .build();
        try {
            BackgroundTask task = repository.putTask(
                    context,
                    "task-test",
                    "researcher",
                    "session-test",
                    new TaskRunSpec.LocalTaskRunSpec(() -> "done"));

            assertTrue(task.waitForCompletion(5_000));
            assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
            assertEquals(1, repository.listTasks(context, "session-test", null).size());
        } finally {
            repository.shutdown();
        }
    }
}
