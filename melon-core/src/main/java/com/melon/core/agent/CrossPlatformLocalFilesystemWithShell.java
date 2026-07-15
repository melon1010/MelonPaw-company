package com.melon.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;

import java.nio.file.Path;
import java.util.Map;

/**
 * Adapts AgentScope's POSIX working-directory prefix for the Windows cmd shell.
 */
final class CrossPlatformLocalFilesystemWithShell extends LocalFilesystemWithShell {

    private static final String WORKDIR_PREFIX = "cd '";
    private static final String WORKDIR_SEPARATOR = "' && ";

    CrossPlatformLocalFilesystemWithShell(
            Path rootDir,
            LocalFsMode mode,
            PathPolicy pathPolicy,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory,
            Path shellCwd) {
        super(rootDir, mode, pathPolicy, timeout, maxOutputBytes, env, inheritEnv,
                namespaceFactory, shellCwd);
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        if (!isWindows()) {
            return super.execute(runtimeContext, command, timeoutSeconds);
        }

        String normalized = normalizeWindowsWorkingDirectory(command);
        if (normalized == null) {
            return new ExecuteResponse(
                    "Error: working_directory must be a relative path within the workspace.",
                    1,
                    false);
        }

        // AgentScope decodes process output as UTF-8. Align cmd and common script runtimes with it.
        String utf8Command = "chcp 65001>nul && set \"PYTHONIOENCODING=utf-8\" && " + normalized;
        return super.execute(runtimeContext, utf8Command, timeoutSeconds);
    }

    static String normalizeWindowsWorkingDirectory(String command) {
        if (command == null || !command.startsWith(WORKDIR_PREFIX)) {
            return command;
        }
        int separator = command.indexOf(WORKDIR_SEPARATOR, WORKDIR_PREFIX.length());
        if (separator < 0) {
            return command;
        }

        String workingDirectory = command.substring(WORKDIR_PREFIX.length(), separator);
        if (workingDirectory.isBlank()
                || workingDirectory.contains("..")
                || workingDirectory.startsWith("/")
                || workingDirectory.startsWith("\\")
                || workingDirectory.matches("^[A-Za-z]:.*")) {
            return null;
        }
        if (workingDirectory.contains("'") || workingDirectory.contains("\"")) {
            return null;
        }

        String remainder = command.substring(separator + WORKDIR_SEPARATOR.length());
        return "cd /d \"" + workingDirectory.replace('/', '\\') + "\" && " + remainder;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
