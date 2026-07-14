package com.melon.app.auth;

import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void isolatesDocumentsBetweenProjectsAndPreservesMarkdown() throws Exception {
        ConfigManager configManager = configManager();
        ProjectWorkspaceService service = new ProjectWorkspaceService(configManager);

        String markdown = "  # Project One\n\ncontent with trailing space  \n";
        service.write("project-one", "project.md", markdown);

        Path first = service.workspaceDir("project-one");
        Path second = service.workspaceDir("project-two");
        assertNotEquals(first, second);
        assertEquals(configManager.resolveHomeDir().resolve("project-workspaces").toAbsolutePath().normalize(),
                first.getParent());
        assertFalse(first.startsWith(configManager.resolveWorkspaceRootDir()),
                "shared project documents must not live in an Agent workspace");

        Map<String, Object> firstProject = document(service.list("project-one"), "PROJECT.md");
        Map<String, Object> secondProject = document(service.list("project-two"), "PROJECT.md");
        assertEquals(markdown, firstProject.get("content"));
        assertEquals("# Project\n", secondProject.get("content"));
        assertEquals(3, service.list("project-one").size());
        assertTrue(Files.isRegularFile(first.resolve("KNOWLEDGE.md")));
    }

    @Test
    void rejectsTraversalAbsolutePathsAndUnsupportedDocuments() throws Exception {
        ProjectWorkspaceService service = new ProjectWorkspaceService(configManager());

        for (String projectId : List.of("", ".", "..", "../outside", "..\\outside",
                "/outside", "project/child", "project\\child", "C:\\outside")) {
            assertThrows(IllegalArgumentException.class, () -> service.workspaceDir(projectId), projectId);
        }

        for (String document : List.of("", "OTHER.md", "../PROJECT.md", "PROJECT.md/..",
                "C:\\PROJECT.md")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.write("project-one", document, "unsafe"), document);
        }

        assertFalse(Files.exists(tempDir.resolve("outside")));
    }

    @Test
    void rejectsOversizedDocuments() {
        ProjectWorkspaceService service = new ProjectWorkspaceService(configManager());
        String oversized = "x".repeat(ProjectWorkspaceService.MAX_DOCUMENT_BYTES + 1);

        assertThrows(IllegalArgumentException.class,
                () -> service.write("project-one", "PROJECT.md", oversized));
    }

    private ConfigManager configManager() {
        ConfigManager manager = new ConfigManager();
        manager.setConfigPath(tempDir.resolve("config.yaml"));
        manager.setHomeDir(tempDir.resolve("home").toString());
        manager.load();
        return manager;
    }

    private Map<String, Object> document(List<Map<String, Object>> documents, String name) {
        return documents.stream()
                .filter(document -> name.equals(document.get("name")))
                .findFirst()
                .orElseThrow();
    }
}
