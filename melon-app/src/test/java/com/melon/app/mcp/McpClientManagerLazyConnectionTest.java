package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class McpClientManagerLazyConnectionTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsCachedToolsWithoutStartingTheStdioProcess() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        Path persisted = tempDir.resolve("mcp-clients.json");
        McpClientManager.McpServerConfig server = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "cached",
                "name", "Cached MCP",
                "transport", "stdio",
                "command", "unused",
                "enabled", true,
                "tools", List.of(Map.of(
                        "name", "lookup",
                        "description", "Lookup data",
                        "input_schema", Map.of("type", "object")))));
        JsonUtils.save(persisted, Map.of("servers", List.of(server.toMap())));
        StdioMcpProcessLauncher launcher = mock(StdioMcpProcessLauncher.class);
        McpClientManager manager = new McpClientManager(
                configManager, persisted, tempDir.resolve("workspace"), launcher);
        try {
            manager.init();

            assertFalse(manager.getConnectionState("cached").connected());
            assertEquals(1, manager.listAllTools().size());
            verify(launcher, never()).start(any(), any());
        } finally {
            manager.shutdown();
        }
    }
}
