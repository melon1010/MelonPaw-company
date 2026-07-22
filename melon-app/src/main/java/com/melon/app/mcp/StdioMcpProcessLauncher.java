package com.melon.app.mcp;

import com.melon.core.agent.SandboxWorkspacePathResolver;
import com.melon.core.util.SafePathUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts stdio MCP servers locally for development or in a restricted Docker container. */
@Component
public class StdioMcpProcessLauncher {

    private static final List<String> DOCKER_ENVIRONMENT = List.of(
            "PATH", "Path", "PATHEXT", "SystemRoot", "ComSpec", "HOME", "USERPROFILE",
            "TEMP", "TMP", "LANG", "LC_ALL", "DOCKER_HOST", "DOCKER_CONTEXT",
            "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH");
    private final String applicationSandboxMode;

    public StdioMcpProcessLauncher() {
        this(null);
    }

    @Autowired
    public StdioMcpProcessLauncher(
            @Value("${melon.execution.sandbox-mode:docker}") String applicationSandboxMode) {
        this.applicationSandboxMode = applicationSandboxMode;
    }

    public LaunchedProcess start(McpClientManager.McpServerConfig config, Path workspace) throws IOException {
        validateCommand(config);
        String executionMode = mode();
        return switch (executionMode) {
            case "local" -> startLocal(config, workspace);
            case "docker" -> startDocker(config, workspace);
            case "disabled" -> throw new IOException(
                    "stdio MCP is disabled by MELON_MCP_STDIO_MODE");
            default -> throw new IOException("Unsupported MELON_MCP_STDIO_MODE: " + executionMode);
        };
    }

    List<String> dockerCommand(McpClientManager.McpServerConfig config, Path workspace,
                               String containerName, Path environmentFile) {
        validateCommand(config);
        List<String> command = new ArrayList<>();
        command.add(setting("MELON_DOCKER_COMMAND", "docker"));
        command.addAll(List.of(
                "run", "--rm", "-i",
                "--name", containerName,
                "--label", "melonpaw.runtime=mcp",
                "--network", setting("MELON_MCP_SANDBOX_NETWORK",
                        setting("MELON_SANDBOX_NETWORK", "bridge")),
                "--memory", setting("MELON_MCP_SANDBOX_MEMORY", "512m"),
                "--cpus", setting("MELON_MCP_SANDBOX_CPUS", "2"),
                "--pids-limit", setting("MELON_MCP_SANDBOX_PIDS", "256"),
                "--user", setting("MELON_MCP_SANDBOX_USER", "10001:10001"),
                "--init",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true"));

        if (workspace != null) {
            Path hostWorkspace = SandboxWorkspacePathResolver.resolveHostPath(workspace);
            String source = hostWorkspace.toString();
            if (source.contains(",")) {
                throw new IllegalArgumentException("Docker workspace path cannot contain a comma");
            }
            command.addAll(List.of(
                    "--mount", "type=bind,source=" + source + ",target=/workspace",
                    "--workdir", dockerWorkingDirectory(config, workspace)));
        } else {
            command.addAll(List.of("--workdir", "/workspace"));
        }

        if (environmentFile != null) {
            command.add("--env-file");
            command.add(environmentFile.toString());
        }
        command.add(setting("MELON_MCP_SANDBOX_IMAGE",
                setting("MELON_SANDBOX_IMAGE", "melonpaw-company-sandbox:latest")));
        command.add(config.getCommand());
        if (config.getArgs() != null) command.addAll(config.getArgs());
        return command;
    }

    private LaunchedProcess startDocker(McpClientManager.McpServerConfig config, Path workspace)
            throws IOException {
        String containerName = containerName(config.getId());
        Path environmentFile = writeEnvironmentFile(config);
        List<String> command;
        try {
            command = dockerCommand(config, workspace, containerName, environmentFile);
        } catch (RuntimeException e) {
            Files.deleteIfExists(environmentFile);
            throw e;
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
        retainEnvironment(builder.environment(), DOCKER_ENVIRONMENT);
        try {
            return new LaunchedProcess(builder.start(), command.get(0), containerName, environmentFile);
        } catch (IOException e) {
            Files.deleteIfExists(environmentFile);
            throw new IOException("Unable to start sandboxed stdio MCP container", e);
        }
    }

    private LaunchedProcess startLocal(McpClientManager.McpServerConfig config, Path workspace)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null) command.addAll(config.getArgs());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
        Path cwd = localWorkingDirectory(config, workspace);
        if (cwd != null) builder.directory(cwd.toFile());
        retainEnvironment(builder.environment(), List.of(
                "PATH", "Path", "PATHEXT", "SystemRoot", "ComSpec", "HOME", "USERPROFILE",
                "TEMP", "TMP", "LANG", "LC_ALL"));
        builder.environment().putAll(configuredEnvironment(config));
        return new LaunchedProcess(builder.start(), null, null, null);
    }

    private Path localWorkingDirectory(McpClientManager.McpServerConfig config, Path workspace) {
        if (workspace == null) {
            return config.getCwd() == null || config.getCwd().isBlank()
                    ? null : Path.of(config.getCwd()).toAbsolutePath().normalize();
        }
        Path root = workspace.toAbsolutePath().normalize();
        if (config.getCwd() == null || config.getCwd().isBlank()) return root;
        Path requested = Path.of(config.getCwd());
        Path resolved = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : SafePathUtil.resolveSafe(root, config.getCwd());
        if (!SafePathUtil.isWithinDir(resolved, root)) {
            throw new IllegalArgumentException("stdio MCP cwd must stay inside its Agent workspace");
        }
        return resolved;
    }

    private String dockerWorkingDirectory(McpClientManager.McpServerConfig config, Path workspace) {
        if (config.getCwd() == null || config.getCwd().isBlank()) return "/workspace";
        Path value = Path.of(config.getCwd());
        Path relative;
        if (!value.isAbsolute()) {
            relative = value.normalize();
        } else if (value.startsWith(Path.of("/workspace"))) {
            relative = Path.of("/workspace").relativize(value.normalize());
        } else {
            Path root = workspace.toAbsolutePath().normalize();
            Path requested = value.toAbsolutePath().normalize();
            if (!requested.startsWith(root)) {
                throw new IllegalArgumentException("stdio MCP cwd must stay inside its Agent workspace");
            }
            relative = root.relativize(requested);
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("stdio MCP cwd must stay inside its Agent workspace");
        }
        String suffix = relative.toString().replace(File.separatorChar, '/');
        return suffix.isBlank() ? "/workspace" : "/workspace/" + suffix;
    }

    private Map<String, String> configuredEnvironment(McpClientManager.McpServerConfig config) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("npm_config_yes", "true");
        if (config.getEnv() == null) return environment;
        config.getEnv().forEach((name, value) -> {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid stdio MCP environment variable name: " + name);
            }
            if (value == null) {
                throw new IllegalArgumentException("stdio MCP environment variable value cannot be null: " + name);
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "stdio MCP environment variable value cannot contain line breaks or NUL: " + name);
            }
            environment.put(name, value);
        });
        return environment;
    }

    private Path writeEnvironmentFile(McpClientManager.McpServerConfig config) throws IOException {
        Path file = Files.createTempFile("melonpaw-mcp-env-", ".list");
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
            StringBuilder content = new StringBuilder();
            configuredEnvironment(config).forEach((name, value) ->
                    content.append(name).append('=').append(value).append('\n'));
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    private void retainEnvironment(Map<String, String> environment, List<String> allowlist) {
        Map<String, String> inherited = new LinkedHashMap<>(environment);
        environment.clear();
        for (String key : allowlist) {
            String value = inherited.get(key);
            if (value != null && !value.isBlank()) environment.put(key, value);
        }
    }

    private String mode() {
        String configured = optionalSetting("MELON_MCP_STDIO_MODE");
        if (configured == null) configured = applicationSandboxMode;
        if (configured == null || configured.isBlank()) {
            configured = setting("MELON_EXECUTION_SANDBOX", "docker");
        }
        return configured.trim().toLowerCase(Locale.ROOT);
    }

    private String containerName(String id) {
        String safe = id == null ? "server" : id.replaceAll("[^A-Za-z0-9_.-]", "-");
        if (safe.length() > 40) safe = safe.substring(0, 40);
        return "melonpaw-mcp-" + safe + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void validateCommand(McpClientManager.McpServerConfig config) {
        if (config == null || config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalArgumentException("stdio MCP command is required");
        }
    }

    private String setting(String name, String fallback) {
        String value = optionalSetting(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String optionalSetting(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value;
    }

    public static final class LaunchedProcess implements AutoCloseable {
        private final Process process;
        private final String dockerCommand;
        private final String containerName;
        private final Path environmentFile;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LaunchedProcess(Process process, String dockerCommand, String containerName,
                                Path environmentFile) {
            this.process = process;
            this.dockerCommand = dockerCommand;
            this.containerName = containerName;
            this.environmentFile = environmentFile;
        }

        public Process process() {
            return process;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            if (dockerCommand != null && containerName != null) {
                try {
                    Process cleanup = new ProcessBuilder(dockerCommand, "rm", "-f", containerName)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
                    if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
                } catch (IOException ignored) {
                    // The Docker CLI process is already stopped; --rm normally removes the container.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (environmentFile != null) {
                try {
                    Files.deleteIfExists(environmentFile);
                } catch (IOException ignored) {
                    // The file has owner-only permissions and will be removed by normal temp cleanup.
                }
            }
        }
    }
}
