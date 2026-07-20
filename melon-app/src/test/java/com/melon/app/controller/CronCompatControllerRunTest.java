package com.melon.app.controller;

import com.melon.app.auth.EnterpriseIdentityStore;
import com.melon.app.cron.CronExecutor;
import com.melon.app.cron.CronManager;
import com.melon.app.cron.CronSchedulingService;
import com.melon.app.service.InboxStore;
import com.melon.channels.ChannelConfigService;
import com.melon.core.config.ConfigManager;
import com.melon.core.cron.CronJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CronCompatControllerRunTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void manualRunUsesAValidTraceIdBeforeDispatch() throws Exception {
        String agentId = "personal-test";
        Path workspace = temporaryDirectory.resolve("workspace");
        Path home = temporaryDirectory.resolve("home");
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.resolveWorkspaceDir(agentId)).thenReturn(workspace);
        when(configManager.resolveHomeDir()).thenReturn(home);
        EnterpriseIdentityStore identityStore = mock(EnterpriseIdentityStore.class);
        when(identityStore.findAgentBinding(agentId)).thenReturn(Optional.empty());
        InboxStore inboxStore = new InboxStore(configManager);
        CronExecutor cronExecutor = mock(CronExecutor.class);
        CronManager cronManager = new CronManager(null);

        try {
            CronSchedulingService schedulingService = new CronSchedulingService(cronManager);
            Map<String, Object> job = CronJobStore.create(workspace, Map.of(
                    "name", "manual-run",
                    "cron", "0 9 * * *",
                    "prompt", "Create the report",
                    "owner_user_id", "user-1"
            ));
            CronCompatController controller = new CronCompatController(
                    configManager,
                    schedulingService,
                    cronExecutor,
                    mock(ChannelConfigService.class),
                    inboxStore,
                    identityStore);

            var response = controller.runJob(String.valueOf(job.get("id")), agentId).block();

            assertNotNull(response);
            assertEquals(200, response.getStatusCode().value());
            List<Path> traces;
            try (var files = Files.list(home.resolve("inbox_traces"))) {
                traces = files.toList();
            }
            assertEquals(1, traces.size());
            String traceId = traces.get(0).getFileName().toString().replaceFirst("\\.json$", "");
            assertEquals("running", inboxStore.getTrace(traceId).get("status"));
            verify(cronExecutor).injectDispatchedMessage(
                    eq(agentId), eq("Create the report"), eq(traceId), any(), any(), any());

            Map<String, Object> updated = CronJobStore.find(workspace, String.valueOf(job.get("id")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) updated.get("history");
            assertEquals("running", history.get(0).get("status"));
        } finally {
            cronManager.shutdown();
        }
    }
}
