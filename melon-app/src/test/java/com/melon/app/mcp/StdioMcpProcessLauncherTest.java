package com.melon.app.mcp;

import com.melon.app.config.McpExecutionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMcpProcessLauncherTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSettings() {
        for (String name : List.of(
                "MELON_WORKSPACE_ROOT", "MELON_SANDBOX_HOST_WORKSPACE_ROOT",
                "MELON_MCP_SANDBOX_IMAGE", "MELON_MCP_SANDBOX_NETWORK",
                "MELON_MCP_SANDBOX_USER", "MELON_SANDBOX_RUNTIME",
                "MELON_MCP_SANDBOX_RUNTIME")) {
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
                "/home/melon:rw,exec,nosuid,nodev,size=256m,uid=10001,gid=10001,mode=0700",
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
    void appliesSpringMcpSandboxConfiguration() {
        McpExecutionProperties properties = new McpExecutionProperties();
        properties.setDockerCommand("docker-custom");
        properties.setSandboxImage("mcp-yaml:test");
        properties.setSandboxNetwork("yaml-egress");
        properties.setSandboxRuntime("runsc");
        properties.setSandboxMemory("768m");
        properties.setSandboxCpus("3");
        properties.setSandboxPids("128");
        properties.setSandboxUser("10002:10003");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "yaml-settings",
                "command", "npx"));

        List<String> command = new StdioMcpProcessLauncher(
                "docker", new McpArtifactImporter(), properties).dockerCommand(
                config, Path.of("/app/workspaces/personal-1"), "mcp-test", null);

        assertTrue(command.containsAll(List.of(
                "docker-custom", "mcp-yaml:test", "yaml-egress", "runsc",
                "768m", "3", "128", "10002:10003")));
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

    @Test
    void appliesConfiguredNonRootUserToExecutableHomeTmpfs() {
        System.setProperty("MELON_MCP_SANDBOX_USER", "10002:10003");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "custom-user",
                "command", "npx"));

        List<String> command = new StdioMcpProcessLauncher().dockerCommand(
                config, Path.of("/app/workspaces/personal-1"), "mcp-test", null);

        assertTrue(command.contains("10002:10003"));
        assertTrue(command.contains(
                "/home/melon:rw,exec,nosuid,nodev,size=256m,uid=10002,gid=10003,mode=0700"));
    }

    @Test
    void rejectsRootMcpSandboxUser() {
        System.setProperty("MELON_MCP_SANDBOX_USER", "0:0");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "root-user",
                "command", "npx"));

        assertThrows(IllegalArgumentException.class, () -> new StdioMcpProcessLauncher().dockerCommand(
                config, Path.of("/app/workspaces/personal-1"), "mcp-test", null));
    }

    @Test
    void appliesConfiguredGvisorRuntimeWithoutFallback() {
        System.setProperty("MELON_SANDBOX_RUNTIME", "runsc");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "gvisor",
                "command", "npx"));

        List<String> command = new StdioMcpProcessLauncher().dockerCommand(
                config, Path.of("/app/workspaces/personal-1"), "mcp-test", null);

        assertTrue(command.containsAll(List.of("--runtime", "runsc")));
    }

    @Test
    void mapsHostScriptInsideWorkspaceToContainerPath() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path script = Files.writeString(workspace.resolve("server.js"), "");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "workspace-server",
                "command", "node",
                "args", List.of(script.toString())));

        List<String> command = new StdioMcpProcessLauncher().dockerCommand(
                config, workspace, "mcp-test", null);

        assertTrue(command.contains("/workspace/server.js"));
        assertFalse(command.contains(script.toString()));
    }

    @Test
    void rejectsExistingHostScriptOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("server.js"), "");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "outside-server",
                "command", "node",
                "args", List.of(outside.toString())));

        assertThrows(IllegalArgumentException.class, () -> new StdioMcpProcessLauncher().dockerCommand(
                config, workspace, "mcp-test", null));
    }

    @Test
    void preparesImportedDependenciesWithoutImageSpecificHelperScript() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path artifact = Files.createDirectories(workspace.resolve("server"));
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "portable-prepare",
                "command", "node"));
        McpArtifactImporter.PreparedMcp prepared = new McpArtifactImporter.PreparedMcp(
                config, McpArtifactImporter.PreparationKind.NODE, artifact,
                "/workspace/server", "content-hash");

        List<String> command = new StdioMcpProcessLauncher().preparationCommand(
                config, prepared, workspace, "mcp-prepare", Path.of("/tmp/mcp.env"));

        assertTrue(command.containsAll(List.of("/bin/sh", "-eu", "-c", "node", "/workspace/server")));
        assertFalse(command.contains("/opt/melonpaw-runtime/prepare-mcp.sh"));
        assertTrue(command.stream().anyMatch(value -> value.contains("MELON_MCP_RUNTIME_MISSING")));
    }
}
