package com.melon.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigManagerWorkspaceRootTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSetting() {
        System.clearProperty("MELON_WORKSPACE_ROOT");
    }

    @Test
    void environmentWorkspaceRootOverridesFileConfiguration() {
        ConfigManager manager = new ConfigManager();
        manager.getConfig().setPaths(new MelonConfig.PathsConfig());
        manager.getConfig().getPaths().setWorkspaceRoot("/from-config");
        System.setProperty("MELON_WORKSPACE_ROOT", "/from-environment");

        assertEquals(Path.of("/from-environment"), manager.resolveWorkspaceRootDir());
    }

    @Test
    void rejectsRelativeEnvironmentWorkspaceRoot() {
        System.setProperty("MELON_WORKSPACE_ROOT", "relative/workspaces");
        assertThrows(IllegalStateException.class,
                () -> new ConfigManager().resolveWorkspaceRootDir());
    }

    @Test
    void rejectsSymbolicLinkAtDefaultAgentWorkspace() throws IOException {
        Path root = tempDir.resolve("workspaces");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root.resolve("project-a"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        System.setProperty("MELON_WORKSPACE_ROOT", root.toString());
        ConfigManager manager = new ConfigManager();
        LinkedHashMap<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("project-a", new AgentConfig());
        manager.getConfig().setAgents(agents);

        assertThrows(IllegalArgumentException.class,
                () -> manager.resolveWorkspaceDir("project-a"));
    }
}
