package com.melon.app.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMcpProcessLauncherTest {

    @AfterEach
    void clearSettings() {
        for (String name : List.of(
                "MELON_WORKSPACE_ROOT", "MELON_SANDBOX_HOST_WORKSPACE_ROOT",
                "MELON_MCP_SANDBOX_IMAGE", "MELON_MCP_SANDBOX_NETWORK")) {
            System.clearProperty(name);
        }
    }

    @Test
    void buildsRestrictedContainerCommandForAgentWorkspace() {
        System.setProperty("MELON_WORKSPACE_ROOT", "/app/workspaces");
        System.setProperty("MELON_SANDBOX_HOST_WORKSPACE_ROOT", "/srv/melon/workspaces");
        System.setProperty("MELON_MCP_SANDBOX_IMAGE", "melon-mcp:test");
        System.setProperty("MELON_MCP_SANDBOX_NETWORK", "restricted-egress");

        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "mind-map",
                "command", "npx",
                "args", List.of("-y", "@example/mcp"),
                "cwd", "project",
                "env", Map.of("MCP_TOKEN", "secret")));
        List<String> command = new StdioMcpProcessLauncher().dockerCommand(
                config, Path.of("/app/workspaces/personal-1"), "mcp-test", Path.of("/tmp/mcp.env"));

        assertTrue(command.containsAll(List.of(
                "--rm", "--init", "--user", "10001:10001",
                "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,size=256m",
                "/home/melon:rw,nosuid,nodev,size=256m",
                "--cap-drop", "ALL", "no-new-privileges:true",
                "restricted-egress", "melon-mcp:test", "npx", "@example/mcp")));
        assertTrue(command.contains(
                "type=bind,source=/srv/melon/workspaces/personal-1,target=/workspace"));
        assertTrue(command.contains("/workspace/project"));
        assertTrue(command.containsAll(List.of("--env-file", "/tmp/mcp.env")));
        assertFalse(command.contains("MCP_TOKEN"));
        assertFalse(command.contains("secret"));
    }

    @Test
    void rejectsWorkingDirectoryOutsideAgentWorkspace() {
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "unsafe",
                "command", "node",
                "cwd", "/etc"));
        assertThrows(IllegalArgumentException.class, () -> new StdioMcpProcessLauncher().dockerCommand(
                config, Path.of("/app/workspaces/personal-1"), "mcp-test", Path.of("/tmp/mcp.env")));
    }
}
