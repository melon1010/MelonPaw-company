package com.melon.app.task;

import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal executable check for persistent admission and per-user concurrency. */
public final class EnterpriseQuotaServiceSelfCheck {

    private EnterpriseQuotaServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("melon-quota-");
        Path database = directory.resolve("enterprise.db");
        new EnterpriseTaskService(database);
        EnterpriseQuotaService quota = new EnterpriseQuotaService(database);
        quota.reserve("task-1", "run-1", "AGENT", "user-1", "PERSONAL", "user-1", "agent-1");
        quota.reserve("task-2", "run-2", "AGENT", "user-1", "PERSONAL", "user-1", "agent-2");
        quota.reserve("task-3", "run-3", "AGENT", "user-1", "PERSONAL", "user-1", "agent-3");
        try {
            quota.reserve("task-4", "run-4", "AGENT", "user-1", "PERSONAL", "user-1", "agent-4");
            throw new AssertionError("Per-user concurrency limit was not enforced");
        } catch (EnterpriseQuotaService.QuotaExceededException expected) {
            if (!"USER".equals(expected.scopeType())) throw expected;
        }
        quota.release("run-1", "SUCCEEDED");
        quota.reserve("task-4", "run-4", "AGENT", "user-1", "PERSONAL", "user-1", "agent-4");
    }
}
