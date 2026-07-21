package com.melon.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentTaskCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void spawnDefaultsToBackgroundAndReusesUndeliveredTaskAcrossReload() {
        FakeRepository repository = new FakeRepository();
        AtomicInteger calls = new AtomicInteger();
        AgentTool delegate = spawnDelegate(repository, calls);
        Path ledgerFile = tempDir.resolve("invocations.json");
        RuntimeContext context = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        ToolCallParam call = ToolCallParam.builder()
                .runtimeContext(context)
                .input(Map.of("agent_id", "researcher", "task", "collect evidence"))
                .build();

        SubagentTaskCoordinator.ManagedAgentSpawnTool first =
                new SubagentTaskCoordinator.ManagedAgentSpawnTool(
                        delegate, repository, new SubagentTaskCoordinator.InvocationLedger(ledgerFile));
        String firstResult = text(first.callAsync(call).block());

        assertEquals(1, calls.get());
        assertEquals(0, repository.lastTimeoutSeconds);
        assertTrue(firstResult.contains("task_id: task_1"));

        SubagentTaskCoordinator.ManagedAgentSpawnTool afterReload =
                new SubagentTaskCoordinator.ManagedAgentSpawnTool(
                        delegate, repository, new SubagentTaskCoordinator.InvocationLedger(ledgerFile));
        String reused = text(afterReload.callAsync(call).block());

        assertEquals(1, calls.get());
        assertTrue(reused.contains("task_id: task_1"));
        assertTrue(reused.contains("reused"));

        repository.futures.get("task_1").complete("done");
        repository.markDelivered(context, "s1", "task_1");
        String secondRun = text(afterReload.callAsync(call).block());

        assertEquals(2, calls.get());
        assertTrue(secondRun.contains("task_id: task_2"));

        repository.cancelTask(context, "s1", "task_2");
        String afterCancellation = text(afterReload.callAsync(call).block());
        assertEquals(3, calls.get());
        assertTrue(afterCancellation.contains("task_id: task_3"));
    }

    @Test
    void waitTimeoutReturnsRunningSnapshotWithoutCancellingTask() {
        FakeRepository repository = new FakeRepository();
        repository.put("task_a", "researcher", CompletableFuture.completedFuture("A"));
        CompletableFuture<String> running = new CompletableFuture<>();
        repository.put("task_b", "reviewer", running);
        RuntimeContext context = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        SubagentTaskCoordinator.TaskWaitTool wait = new SubagentTaskCoordinator.TaskWaitTool(repository);

        ToolCallParam timeoutCall = ToolCallParam.builder()
                .runtimeContext(context)
                .input(Map.of("task_ids", List.of("task_a", "task_b"),
                        "mode", "wait_all", "timeout_ms", 5))
                .build();
        String timedOut = text(wait.callAsync(timeoutCall).block());

        assertTrue(timedOut.contains("task_id: task_b\nstatus: running"));
        assertTrue(timedOut.contains("still running"));
        assertEquals(TaskStatus.RUNNING, repository.tasks.get("task_b").getTaskStatus());

        running.complete("B");
        String completed = text(wait.callAsync(timeoutCall).block());
        assertTrue(completed.contains("task_id: task_b\nstatus: completed"));
        assertTrue(repository.delivered.get("task_b"));
    }

    @Test
    void reviewerReceivesCompletedSourceTaskResults() {
        FakeRepository repository = new FakeRepository();
        repository.put("research_a", "researcher", CompletableFuture.completedFuture("verified evidence"));
        AtomicReference<String> delegatedTask = new AtomicReference<>();
        AgentTool delegate = spawnDelegate(repository, new AtomicInteger(), delegatedTask);
        SubagentTaskCoordinator.ManagedAgentSpawnTool spawn =
                new SubagentTaskCoordinator.ManagedAgentSpawnTool(
                        delegate, repository, new SubagentTaskCoordinator.InvocationLedger(
                                tempDir.resolve("review-invocations.json")));

        spawn.callAsync(ToolCallParam.builder()
                .runtimeContext(RuntimeContext.builder().userId("u1").sessionId("s1").build())
                .input(Map.of("agent_id", "reviewer", "task", "review the evidence",
                        "source_task_ids", List.of("research_a")))
                .build()).block();

        assertTrue(delegatedTask.get().contains("verified evidence"));
        assertTrue(delegatedTask.get().contains("research_a"));
    }

    private AgentTool spawnDelegate(FakeRepository repository, AtomicInteger calls) {
        return spawnDelegate(repository, calls, null);
    }

    private AgentTool spawnDelegate(
            FakeRepository repository, AtomicInteger calls, AtomicReference<String> delegatedTask) {
        return new AgentTool() {
            @Override
            public String getName() {
                return "agent_spawn";
            }

            @Override
            public String getDescription() {
                return "spawn";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                if (delegatedTask != null) delegatedTask.set(String.valueOf(param.getInput().get("task")));
                int number = calls.incrementAndGet();
                repository.lastTimeoutSeconds = ((Number) param.getInput().get("timeout_seconds")).intValue();
                String taskId = "task_" + number;
                repository.put(taskId, String.valueOf(param.getInput().get("agent_id")),
                        new CompletableFuture<>());
                return Mono.just(ToolResultBlock.text("status: accepted\ntask_id: " + taskId));
            }
        };
    }

    private String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    private static final class FakeRepository implements TaskRepository {
        private final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();
        private final Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();
        private final Map<String, Boolean> delivered = new LinkedHashMap<>();
        private int lastTimeoutSeconds = -1;

        private void put(String id, String agent, CompletableFuture<String> future) {
            futures.put(id, future);
            tasks.put(id, new BackgroundTask(id, agent, future));
            delivered.put(id, false);
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return tasks.get(taskId);
        }

        @Override
        public BackgroundTask putTask(RuntimeContext rc, String taskId, String subAgentId,
                                      String sessionId, TaskRunSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {
            tasks.remove(taskId);
        }

        @Override
        public void clear() {
            tasks.clear();
        }

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return tasks.values().stream()
                    .filter(task -> filter == null || task.getTaskStatus() == filter)
                    .toList();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            BackgroundTask task = tasks.get(taskId);
            return task != null && task.cancel(true);
        }

        @Override
        public void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
            delivered.put(taskId, true);
        }

        @Override
        public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
            return Boolean.TRUE.equals(delivered.get(taskId));
        }
    }
}
