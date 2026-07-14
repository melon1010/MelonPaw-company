package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsAndWritesNestedMarkdownInsideAgentWorkspace() throws Exception {
        WorkspaceMemoryService service = service();
        service.write("default", "digest/wiki/topic.md", "today");

        assertEquals("today", service.read("default", "digest/wiki/topic.md").get("content"));
        assertTrue(service.list("default").stream()
                .anyMatch(file -> "digest/wiki/topic.md".equals(file.get("filename"))));
    }

    @Test
    void rejectsTraversalNonMarkdownAndOversizedDocuments() {
        WorkspaceMemoryService service = service();
        assertThrows(IllegalArgumentException.class,
                () -> service.write("default", "../outside.md", "no"));
        assertThrows(IllegalArgumentException.class,
                () -> service.write("default", "memory.txt", "no"));
        assertThrows(IllegalArgumentException.class,
                () -> service.write("default", "large.md", "x".repeat(1024 * 1024 + 1)));
    }

    private WorkspaceMemoryService service() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        SecuritySettingsService security = new SecuritySettingsService(configManager);
        return new WorkspaceMemoryService(configManager, new FileGuardService(security));
    }
}
