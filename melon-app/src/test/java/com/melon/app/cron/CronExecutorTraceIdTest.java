package com.melon.app.cron;

import com.melon.app.service.InboxStore;
import com.melon.channels.ChannelManager;
import com.melon.channels.ChannelOutboundMessage;
import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CronExecutorTraceIdTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void scheduledRunDoesNotUseTheRuntimeJobIdAsATraceFilename() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.resolveHomeDir()).thenReturn(temporaryDirectory);
        InboxStore inboxStore = new InboxStore(configManager);
        ChannelManager channelManager = mock(ChannelManager.class);
        ChannelOutboundMessage reply = new ChannelOutboundMessage();
        reply.setText("done");
        reply.setMeta(Map.of());
        when(channelManager.enqueue(any(), eq(20)))
                .thenReturn(CompletableFuture.completedFuture(reply));
        CronExecutor executor = new CronExecutor(null, null, channelManager, inboxStore, null);
        CronManager.CronJob job = new CronManager.CronJob();
        job.setId("personal-test:job-1");
        job.setSourceId("job-1");
        job.setName("scheduled-run");
        job.setAgentId("personal-test");
        job.setMessage("Create the report");
        job.setDispatch(Map.of(
                "channel", "console",
                "target", Map.of("user_id", "user-1"),
                "meta", Map.of("execution_user_id", "user-1")));

        executor.execute(job);

        Path tracesDir = temporaryDirectory.resolve("inbox_traces");
        try (var files = Files.list(tracesDir)) {
            var traces = files.toList();
            assertEquals(1, traces.size());
            String filename = traces.get(0).getFileName().toString();
            assertTrue(filename.matches("[A-Za-z0-9_-]+\\.json"));
            assertFalse(filename.contains(":"));
        }
    }
}
