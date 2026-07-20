package com.melon.app.cron;

import com.melon.core.cron.CronJobStore;
import com.melon.tools.cron.CreateCronJobTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CronSchedulingServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void contributedToolPersistsAndImmediatelyRegistersTheJob() {
        CronManager manager = new CronManager(null);
        try {
            CronSchedulingService service = new CronSchedulingService(manager);
            CreateCronJobTool tool = new CreateCronJobTool(temporaryDirectory,
                    body -> service.create("personal-test", temporaryDirectory, body));

            tool.callAsync(ToolCallParam.builder().input(Map.of(
                    "name", "daily-summary",
                    "cron", "0 9 * * *",
                    "prompt", "Create the daily summary",
                    "timezone", "Asia/Shanghai"
            )).build()).block();

            List<Map<String, Object>> jobs = CronJobStore.load(temporaryDirectory);
            assertEquals(1, jobs.size());
            String id = String.valueOf(jobs.get(0).get("id"));
            assertNotNull(manager.get("personal-test:" + id));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void nextRunUsesTheJobTimezoneInsteadOfTheJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        CronManager manager = new CronManager(null);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            CronSchedulingService service = new CronSchedulingService(manager);
            Map<String, Object> stored = service.create("personal-test", temporaryDirectory, Map.of(
                    "name", "shanghai-nine",
                    "cron", "0 9 * * *",
                    "prompt", "Run at nine",
                    "timezone", "Asia/Shanghai"
            ));

            CronManager.CronJob runtime = manager.get(
                    "personal-test:" + stored.get("id"));
            ZonedDateTime next = Instant.ofEpochMilli(runtime.getNextRun())
                    .atZone(ZoneId.of("Asia/Shanghai"));
            assertEquals(9, next.getHour());
            assertEquals(0, next.getMinute());
        } finally {
            TimeZone.setDefault(original);
            manager.shutdown();
        }
    }

    @Test
    void invalidCronAndTimezoneAreRejectedBeforePersistence() {
        CronManager manager = new CronManager(null);
        try {
            CronSchedulingService service = new CronSchedulingService(manager);
            assertThrows(IllegalArgumentException.class, () -> service.create(
                    "personal-test", temporaryDirectory, Map.of(
                            "name", "invalid-cron",
                            "cron", "not a cron",
                            "prompt", "Never run")));
            assertThrows(IllegalArgumentException.class, () -> service.create(
                    "personal-test", temporaryDirectory, Map.of(
                            "name", "invalid-zone",
                            "cron", "0 9 * * *",
                            "prompt", "Never run",
                            "timezone", "Mars/Olympus")));

            assertFalse(CronJobStore.jobsFile(temporaryDirectory).toFile().exists());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void localOneShotTimeUsesItsTimezoneAndIsPersistedAsCompletedAfterTrigger() throws Exception {
        CronManager manager = new CronManager(mock(CronExecutor.class));
        try {
            CronSchedulingService service = new CronSchedulingService(manager);
            String runAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                    .plusNanos(400_000_000)
                    .toString();
            Map<String, Object> stored = service.create("personal-test", temporaryDirectory, Map.of(
                    "name", "one-shot",
                    "prompt", "Run once",
                    "schedule", Map.of(
                            "type", "once",
                            "run_at", runAt,
                            "timezone", "Asia/Shanghai")
            ));
            String id = String.valueOf(stored.get("id"));
            assertNotNull(manager.get("personal-test:" + id));

            long deadline = System.currentTimeMillis() + 3_000;
            Map<String, Object> completed = stored;
            while (System.currentTimeMillis() < deadline) {
                completed = CronJobStore.find(temporaryDirectory, id);
                if (completed != null && !Boolean.parseBoolean(String.valueOf(completed.get("enabled")))) break;
                Thread.sleep(25);
            }

            assertNotNull(completed);
            assertFalse(Boolean.parseBoolean(String.valueOf(completed.get("enabled"))));
            assertTrue(completed.containsKey("completed_at"));
            service.syncAll("personal-test", temporaryDirectory, CronJobStore.load(temporaryDirectory));
            assertNull(manager.get("personal-test:" + id));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void persistenceIsRolledBackWhenLiveSchedulingFails() {
        CronManager manager = new CronManager(null) {
            @Override
            public CronJob schedule(CronJob job) {
                throw new IllegalStateException("scheduler unavailable");
            }
        };
        try {
            CronSchedulingService service = new CronSchedulingService(manager);
            assertThrows(IllegalStateException.class, () -> service.create(
                    "personal-test", temporaryDirectory, Map.of(
                            "name", "rollback",
                            "cron", "0 9 * * *",
                            "prompt", "Must not remain persisted")));

            assertEquals(List.of(), CronJobStore.load(temporaryDirectory));
        } finally {
            manager.shutdown();
        }
    }
}
