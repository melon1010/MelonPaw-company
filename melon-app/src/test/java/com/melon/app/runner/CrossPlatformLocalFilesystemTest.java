package com.melon.app.runner;

import com.melon.core.agent.CrossPlatformLocalFilesystemSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossPlatformLocalFilesystemTest {

    @TempDir
    Path workspace;

    @Test
    void executesFromRelativeWorkingDirectoryAndSharesFileToolRoot() throws Exception {
        Files.createDirectories(workspace.resolve("nested"));
        RuntimeContext context = RuntimeContext.builder()
                .userId("user-test")
                .sessionId("session-test")
                .put("agent_id", "personal-test")
                .build();

        CrossPlatformLocalFilesystemSpec spec = new CrossPlatformLocalFilesystemSpec()
                .project(workspace)
                .mode(LocalFsMode.SANDBOXED)
                .executeTimeoutSeconds(10);
        spec.projectWritable(true);
        spec.isolationScope(IsolationScope.AGENT);
        AbstractFilesystem filesystem = spec.toFilesystem(
                workspace, IsolationScope.AGENT.toNamespaceFactory());
        filesystem.write(context, "nested/probe.txt", "portable-shell-\u5357\u4eac");

        String command = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "type probe.txt"
                : "cat probe.txt";
        String output = new ShellExecuteTool((AbstractSandboxFilesystem) filesystem)
                .execute(context, command, "nested", 10);

        assertTrue(output.contains("Exit code: 0"), output);
        assertTrue(output.contains("portable-shell-\u5357\u4eac"), output);
    }
}
