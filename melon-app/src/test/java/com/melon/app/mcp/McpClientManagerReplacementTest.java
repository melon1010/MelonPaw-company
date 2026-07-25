package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpClientManagerReplacementTest {

    @TempDir
    Path tempDir;

    @Test
    void failedPreparationKeepsExistingConfiguration() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        StdioMcpProcessLauncher launcher = new StdioMcpProcessLauncher() {
            @Override
            public void validateAndPrepare(McpClientManager.McpServerConfig config, Path workspace)
                    throws IOException {
                if ("bad-command".equals(config.getCommand())) throw new IOException("prepare failed");
            }
        };
        McpClientManager manager = new McpClientManager(
                configManager, tempDir.resolve("mcp.json"), tempDir.resolve("workspace"), launcher);
        try {
            manager.register(config("stable-command"));

            assertThrows(IllegalArgumentException.class,
                    () -> manager.replace("server", config("bad-command")));

            assertEquals("stable-command", manager.getServer("server").getCommand());
        } finally {
            manager.shutdown();
        }
    }

    private McpClientManager.McpServerConfig config(String command) {
        return McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "server",
                "command", command,
                "enabled", false));
    }
}
