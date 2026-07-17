package com.melon.app.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Runnable smoke check for idempotency, execution exclusion, retry and restart recovery. */
public final class EnterpriseTaskServiceSelfCheck {

    private EnterpriseTaskServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("melon-task-check");
        Path database = directory.resolve("enterprise.db");
        try {
            EnterpriseTaskService service = new EnterpriseTaskService(database);
            EnterpriseTaskService.TaskRun first = service.startPersonal(
                    "AGENT", "user-1", "agent-1", "First", "session-1",
                    "console", "request-1", "chat-1", "request-1");
            EnterpriseTaskService.TaskRun duplicate = service.startPersonal(
                    "AGENT", "user-1", "agent-1", "First", "session-1",
                    "console", "request-1", "chat-1", "request-1");
            require(first.created() && !duplicate.created() && first.taskId().equals(duplicate.taskId()),
                    "idempotency key created a duplicate task");

            try {
                service.startPersonal("AGENT", "user-1", "agent-1", "Concurrent", "session-1",
                        "console", "request-2", "chat-1", "request-2");
                throw new AssertionError("active session accepted a concurrent task");
            } catch (IllegalStateException expected) {
                // Expected: one active writer per user, Agent and session.
            }

            service.succeed(first, "done");
            require("SUCCEEDED".equals(service.get(first.taskId()).get("status")),
                    "successful task did not reach SUCCEEDED");

            EnterpriseTaskService.TaskRun failed = service.startPersonal(
                    "AGENT", "user-1", "agent-1", "Retry", "session-retry",
                    "console", "request-retry", "chat-retry", "request-retry");
            service.setRetryIntent(failed, new EnterpriseTaskService.RetryIntent(
                    "STREAM", "retry me", Map.of("channel", "console")));
            service.fail(failed, new IllegalStateException("first attempt failed"));
            EnterpriseTaskService.RetryStart retry = service.beginRetry(failed.taskId(), "user-1");
            require(failed.taskId().equals(retry.run().taskId())
                            && !failed.runId().equals(retry.run().runId()),
                    "retry did not create a new run under the same task");
            require(service.runs(failed.taskId()).size() == 2
                            && Integer.valueOf(2).equals(service.get(failed.taskId()).get("attempt")),
                    "retry did not preserve the failed run");
            service.succeed(retry.run(), "recovered");

            require(service.beginAgentMutation("agent-2"),
                    "idle Agent rejected a resource mutation lease");
            try {
                service.startPersonal("AGENT", "user-1", "agent-2", "Blocked", "session-mutation",
                        "console", "request-mutation", "chat-mutation", "request-mutation");
                throw new AssertionError("resource mutation lease accepted a new task");
            } catch (IllegalStateException expected) {
                // Expected: a Task cannot capture a half-written Agent resource set.
            } finally {
                service.endAgentMutation("agent-2");
            }
            EnterpriseTaskService.TaskRun afterMutation = service.startPersonal(
                    "AGENT", "user-1", "agent-2", "After mutation", "session-mutation",
                    "console", "request-after-mutation", "chat-mutation", "request-after-mutation");
            service.succeed(afterMutation, "done");

            EnterpriseTaskService.TaskRun unfinished = service.startPersonal(
                    "AUTOMATION", "user-1", "agent-1", "Unfinished", "session-2",
                    "cron", "job-1", "chat-2", "cron:job-1");
            EnterpriseTaskService restarted = new EnterpriseTaskService(database);
            require("INTERRUPTED".equals(restarted.get(unfinished.taskId()).get("status")),
                    "restart did not interrupt unfinished task");
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {
                    }
                });
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
