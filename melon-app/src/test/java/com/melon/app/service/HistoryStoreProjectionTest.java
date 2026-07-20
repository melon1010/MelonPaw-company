package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryStoreProjectionTest {

    @TempDir
    Path home;

    @Test
    void repeatedSnapshotsReplaceTheSessionProjectionAndCollapseSemanticDuplicates() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        HistoryStore store = new HistoryStore(configManager);
        Map<String, Object> state = Map.of("context", List.of(
                message("u1", "user", "question"),
                message("a1", "assistant", "same answer"),
                message("a2", "assistant", "same answer")));

        store.appendSession("default", "session-1", state);
        List<Map<String, Object>> firstProjection = store.session("default", "session-1", 10);
        store.appendSession("default", "session-1", state);

        assertEquals(2, store.count("default", "session-1"));
        List<Map<String, Object>> secondProjection = store.session("default", "session-1", 10);
        assertEquals(2, secondProjection.size());
        assertEquals(firstProjection.stream().map(row -> row.get("seq")).toList(),
                secondProjection.stream().map(row -> row.get("seq")).toList());

        store.appendSession("default", "session-1", Map.of("context", List.of(
                message("u1", "user", "question"))));
        assertEquals(1, store.count("default", "session-1"));
        assertEquals(firstProjection.get(0).get("seq"),
                store.session("default", "session-1", 10).get(0).get("seq"));
        store.shutdown();
    }

    private Map<String, Object> message(String id, String role, String text) {
        return Map.of(
                "id", id,
                "role", role,
                "content", List.of(Map.of("type", "text", "text", text)),
                "metadata", Map.of());
    }
}
