package com.melon.app.config;

import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void createsMissingDirectoryAndUsesOneWatchRegistration() {
        ConfigManager configManager = new ConfigManager();
        Path config = tempDir.resolve("missing").resolve("config.yaml");
        configManager.setConfigPath(config);
        ConfigWatcher watcher = new ConfigWatcher(configManager, true);

        try {
            watcher.start();
            watcher.start();
            assertTrue(watcher.isRunning());
            assertEquals(1, watcher.registrationCount());
            assertEquals(config.getParent(), watcher.watchedDirectory());
        } finally {
            watcher.stop();
        }

        assertFalse(watcher.isRunning());
        assertEquals(0, watcher.registrationCount());
    }
}
