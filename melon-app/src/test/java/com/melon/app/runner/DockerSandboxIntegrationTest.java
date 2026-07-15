package com.melon.app.runner;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerSandboxIntegrationTest {

    @TempDir
    Path workspace;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executesAgentScopeShellToolAsNonRootAgainstMountedWorkspace() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(
                System.getenv().getOrDefault("MELON_DOCKER_INTEGRATION", "false")));

        Path nested = Files.createDirectories(workspace.resolve("nested"));
        if (workspace.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwxrwxrwx"));
            Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("rwxrwxrwx"));
        }

        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/workspace");
        BindMountEntry mount = new BindMountEntry();
        mount.setHostPath(workspace.toAbsolutePath().normalize().toString());
        mount.setReadOnly(false);
        workspaceSpec.getEntries().put(".", mount);

        String image = System.getenv().getOrDefault(
                "MELON_SANDBOX_IMAGE", "melonpaw-company-sandbox:ci");
        DockerFilesystemSpec spec = new DockerFilesystemSpec()
                .image(image)
                .workspaceRoot("/workspace")
                .network("none")
                .additionalRunArgs(
                        "--cap-drop=ALL",
                        "--security-opt=no-new-privileges:true",
                        "--pids-limit=256")
                .workspaceSpec(workspaceSpec);
        spec.isolationScope(IsolationScope.USER);
        spec.workspaceProjectionEnabled(false);

        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("docker-user")
                .sessionId("docker-session")
                .put("agent_id", "personal-docker-test")
                .build();
        SandboxContext context = spec.toSandboxContext(workspace);
        SandboxClient client = context.getClient();
        try (Sandbox sandbox = client.create(
                context.getWorkspaceSpec(),
                context.getSnapshotSpec(),
                context.getClientOptions())) {
            sandbox.start();
            SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
            filesystem.setSandbox(sandbox);
            filesystem.write(runtimeContext, "nested/probe.txt", "docker-shell-ok");

            String output = new ShellExecuteTool(filesystem)
                    .execute(runtimeContext,
                            "test \"$(id -u)\" = 10001 && cat probe.txt",
                            "nested",
                            15);

            assertTrue(output.contains("Exit code: 0"), output);
            assertTrue(output.contains("docker-shell-ok"), output);
        }
    }
}
