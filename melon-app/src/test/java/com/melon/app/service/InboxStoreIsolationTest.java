package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxStoreIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void mutationsAndTracesAreScopedByAgent() {
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("config.yaml"));
        configManager.setHomeDir(tempDir.resolve("home").toString());
        configManager.load();
        InboxStore store = new InboxStore(configManager);

        store.createTrace("run-a", Map.of("agent_id", "personal-a"));
        Map<String, Object> eventA = store.appendEvent(
                "personal-a", "chat", "source-a", "completed", "done", "info",
                "A", "A body", Map.of("run_id", "run-a"));
        Map<String, Object> eventB = store.appendEvent(
                "personal-b", "chat", "source-b", "completed", "done", "info",
                "B", "B body", Map.of());

        assertEquals(1, store.markAllRead("personal-a"));
        assertTrue(Boolean.TRUE.equals(store.listEvents(
                10, 0, null, null, "personal-a", false).get(0).get("read")));
        assertFalse(Boolean.TRUE.equals(store.listEvents(
                10, 0, null, null, "personal-b", false).get(0).get("read")));

        assertFalse(Boolean.TRUE.equals(store.deleteEvent(
                String.valueOf(eventA.get("id")), "personal-b").get("deleted")));
        assertTrue(Boolean.TRUE.equals(store.deleteEvent(
                String.valueOf(eventB.get("id")), "personal-b").get("deleted")));
        assertEquals("not_found", store.getTrace("run-a", "personal-b").get("status"));
        assertEquals("running", store.getTrace("run-a", "personal-a").get("status"));
    }
}
