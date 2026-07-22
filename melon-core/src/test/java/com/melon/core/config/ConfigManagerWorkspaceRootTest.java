package com.melon.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigManagerWorkspaceRootTest {

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
}
