package com.melon.app.auth;

import com.melon.core.config.ConfigManager;
import com.melon.core.config.MelonConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;

class ConfigManagerResilienceTest {

    @TempDir
    Path tempDir;

    @Test
    void malformedReloadKeepsLastKnownGoodConfiguration() throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        ConfigManager manager = new ConfigManager();
        manager.setConfigPath(configFile);
        manager.setHomeDir(tempDir.resolve("home").toString());
        MelonConfig knownGood = manager.load();

        Files.writeString(configFile, "agents: [not-a-map");
        manager.reload();

        assertSame(knownGood, manager.getConfig());
    }
}
