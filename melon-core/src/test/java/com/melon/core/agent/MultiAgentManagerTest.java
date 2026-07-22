package com.melon.core.agent;

import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiAgentManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void internalDefaultTemplateCannotBecomeARuntimeAgent() {
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("config.yaml"));
        configManager.setHomeDir(tempDir.resolve("home").toString());
        MultiAgentManager manager = new MultiAgentManager(configManager, new WorkspaceManager());

        assertThrows(IllegalArgumentException.class,
                () -> manager.getOrCreate(ConfigManager.DEFAULT_AGENT_TEMPLATE_ID));
        assertFalse(Files.exists(configManager.resolveWorkspaceDir(
                ConfigManager.DEFAULT_AGENT_TEMPLATE_ID)));
    }
}
