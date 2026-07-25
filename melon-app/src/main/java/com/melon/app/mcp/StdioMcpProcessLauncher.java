package com.melon.app.mcp;

import com.melon.app.config.McpExecutionProperties;
import com.melon.core.agent.SandboxWorkspacePathResolver;
import com.melon.core.util.SafePathUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts stdio MCP servers locally for development or in a restricted Docker container. */
@Component
public class StdioMcpProcessLauncher {

    private static final String MCP_PREPARATION_SCRIPT = """
            kind="$1"
            root="$2"

            case "$root" in
              /workspace | /workspace/*) ;;
              *)
                echo "MCP preparation directory must stay inside /workspace" >&2
                exit 64
                ;;
            esac

            test -d "$root"
            cd "$root"

            require_command() {
              if ! command -v "$1" >/dev/null 2>&1; then
                echo "MELON_MCP_RUNTIME_MISSING: $1" >&2
                exit 127
              fi
            }

            case "$kind" in
              node)
                require_command npm
                test -f package.json
                if test -f package-lock.json || test -f npm-shrinkwrap.json; then
                  npm ci --include=dev --no-audit --no-fund
                else
                  npm install --include=dev --no-audit --no-fund --no-package-lock
                fi
                ;;
              python-uv)
                require_command uv
                test -f pyproject.toml
                if test -f uv.lock; then
                  uv sync --frozen --no-progress
                else
                  uv sync --no-progress
                fi
                ;;
              python-requirements)
                require_command python3
                test -f requirements.txt
                python3 -m venv .venv
                .venv/bin/python -m pip install --disable-pip-version-check -r requirements.txt
                ;;
              *)
                echo "Unsupported MCP preparation type: $kind" >&2
                exit 64
                ;;
            esac
            """;
    private static final List<String> DOCKER_ENVIRONMENT = List.of(
            "PATH", "Path", "PATHEXT", "SystemRoot", "ComSpec", "HOME", "USERPROFILE",
            "TEMP", "TMP", "LANG", "LC_ALL", "DOCKER_HOST", "DOCKER_CONTEXT",
            "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH");
    private static final ConcurrentHashMap<Path, Object> PREPARATION_LOCKS = new ConcurrentHashMap<>();
    private final String applicationSandboxMode;
    private final McpArtifactImporter artifactImporter;
    private final McpExecutionProperties configuredMcp;

    public StdioMcpProcessLauncher() {
        this(null, new McpArtifactImporter(), null);
    }

    public StdioMcpProcessLauncher(
            @Value("${melon.execution.sandbox-mode:docker}") String applicationSandboxMode) {
        this(applicationSandboxMode, new McpArtifactImporter(), null);
    }

    @Autowired
    public StdioMcpProcessLauncher(
            @Value("${melon.execution.sandbox-mode:docker}") String applicationSandboxMode,
            McpArtifactImporter artifactImporter,
            McpExecutionProperties configuredMcp) {
        this.applicationSandboxMode = applicationSandboxMode;
        this.artifactImporter = artifactImporter;
        this.configuredMcp = configuredMcp;
    }

    public LaunchedProcess start(McpClientManager.McpServerConfig config, Path workspace) throws IOException {
        validateCommand(config);
        String executionMode = mode();
        return switch (executionMode) {
            case "local" -> startLocal(config, workspace);
            case "docker" -> {
                McpArtifactImporter.PreparedMcp prepared = prepareDocker(config, workspace);
                yield startDocker(prepared.runtimeConfig(), workspace);
            }
            case "disabled" -> throw new IOException(
                    "stdio MCP is disabled by MELON_MCP_STDIO_MODE");
            default -> throw new IOException("Unsupported MELON_MCP_STDIO_MODE: " + executionMode);
        };
    }

    /** Resolves and prepares a configuration before it replaces a persisted MCP definition. */
    public void validateAndPrepare(McpClientManager.McpServerConfig config, Path workspace)
            throws IOException {
        validateCommand(config);
        switch (mode()) {
            case "local" -> localWorkingDirectory(config, workspace);
            case "docker" -> prepareDocker(config, workspace);
            case "disabled" -> throw new IOException("stdio MCP is disabled by MELON_MCP_STDIO_MODE");
            default -> throw new IOException("Unsupported MELON_MCP_STDIO_MODE: " + mode());
        }
    }

    List<String> dockerCommand(McpClientManager.McpServerConfig config, Path workspace,
                               String containerName, Path environmentFile) {
        validateCommand(config);
        SandboxUser sandboxUser = sandboxUser();
        List<String> command = new ArrayList<>();
        command.add(setting("MELON_DOCKER_COMMAND", "docker"));
        command.addAll(List.of("run", "--rm", "-i"));
        addRuntime(command);
        command.addAll(List.of(
                "--name", containerName,
                "--label", "melonpaw.runtime=mcp",
                "--network", setting("MELON_MCP_SANDBOX_NETWORK",
                        setting("MELON_SANDBOX_NETWORK", "bridge")),
                "--memory", setting("MELON_MCP_SANDBOX_MEMORY", "512m"),
                "--cpus", setting("MELON_MCP_SANDBOX_CPUS", "2"),
                "--pids-limit", setting("MELON_MCP_SANDBOX_PIDS", "256"),
                "--user", sandboxUser.value(),
                "--init",
                "--read-only",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=256m",
                "--tmpfs", sandboxUser.homeTmpfs(),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true"));

        if (workspace != null) {
            Path hostWorkspace = SandboxWorkspacePathResolver.resolveHostPath(workspace);
            command.addAll(List.of(
                    "--mount", workspaceMount(hostWorkspace),
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
        command.addAll(dockerArguments(config, workspace));
        return command;
    }

    private List<String> dockerArguments(McpClientManager.McpServerConfig config, Path workspace) {
        if (config.getArgs() == null || config.getArgs().isEmpty()) return List.of();
        List<String> arguments = new ArrayList<>(config.getArgs().size());
        for (String argument : config.getArgs()) {
            if (argument == null) {
                throw new IllegalArgumentException("stdio MCP arguments cannot contain null");
            }
            arguments.add(dockerArgument(argument, workspace));
        }
        return List.copyOf(arguments);
    }

    private String dockerArgument(String argument, Path workspace) {
        if (argument.isBlank() || workspace == null) return argument;
        Path value;
        try {
            value = Path.of(argument);
        } catch (RuntimeException ignored) {
            return argument;
        }
        if (!value.isAbsolute()) return argument;

        Path containerRoot = Path.of("/workspace");
        Path normalized = value.normalize();
        if (normalized.startsWith(containerRoot)) {
            return normalized.toString().replace(File.separatorChar, '/');
        }

        Path applicationWorkspace = workspace.toAbsolutePath().normalize();
        if (SafePathUtil.isWithinDir(normalized, applicationWorkspace)) {
            Path relative = applicationWorkspace.relativize(normalized);
            String suffix = relative.toString().replace(File.separatorChar, '/');
            return suffix.isBlank() ? "/workspace" : "/workspace/" + suffix;
        }
        if (Files.exists(normalized)) {
            throw new IllegalArgumentException(
                    "stdio MCP argument references a host path outside its Agent workspace: " + argument
                            + ". Copy the server into the Agent workspace or bake it into the sandbox image.");
        }
        return argument;
    }

    private McpArtifactImporter.PreparedMcp prepareDocker(
            McpClientManager.McpServerConfig config, Path workspace) throws IOException {
        McpArtifactImporter.PreparedMcp prepared = artifactImporter.resolve(config, workspace);
        if (!prepared.requiresPreparation()) return prepared;
        prepareDependencies(config, prepared, workspace);
        return prepared;
    }

    private void prepareDependencies(McpClientManager.McpServerConfig original,
                                     McpArtifactImporter.PreparedMcp prepared,
                                     Path workspace) throws IOException {
        if (workspace == null || prepared.artifactDirectory() == null) {
            throw new IOException("A local stdio MCP artifact requires an Agent workspace");
        }
        Path marker = prepared.preparationMarker();
        if (Files.isRegularFile(marker)) return;
        Object lock = PREPARATION_LOCKS.computeIfAbsent(marker, ignored -> new Object());
        try {
            synchronized (lock) {
                if (Files.isRegularFile(marker)) return;
                prepareCacheDirectories(workspace);
                Path environmentFile = writeEnvironmentFile(original, workspace);
                Path outputFile = Files.createTempFile("melonpaw-mcp-prepare-", ".log");
                String preparationContainer = containerName(original.getId() + "-prepare");
                List<String> command = preparationCommand(
                        original, prepared, workspace, preparationContainer, environmentFile);
                Process process = null;
                try {
                    ProcessBuilder builder = new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(outputFile.toFile());
                    retainEnvironment(builder.environment(), DOCKER_ENVIRONMENT);
                    try {
                        process = builder.start();
                    } catch (IOException e) {
                        throw new IOException("Unable to start the MCP dependency Docker sandbox", e);
                    }
                    long timeout = positiveLongSetting("MELON_MCP_PREPARE_TIMEOUT_SECONDS", 300L);
                    if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        removeContainer(command.get(0), preparationContainer);
                        throw new IOException("Timed out while preparing MCP dependencies after "
                                + timeout + " seconds. " + preparationOutput(outputFile, original));
                    }
                    if (process.exitValue() != 0) {
                        throw new IOException("Failed to prepare MCP dependencies in the sandbox. "
                                + preparationOutput(outputFile, original));
                    }
                    writePreparationMarker(marker, prepared);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (process != null) process.destroyForcibly();
                    removeContainer(command.get(0), preparationContainer);
                    throw new IOException("Interrupted while preparing MCP dependencies", e);
                } finally {
                    deleteQuietly(environmentFile);
                    deleteQuietly(outputFile);
                }
            }
        } finally {
            PREPARATION_LOCKS.remove(marker, lock);
        }
    }

    List<String> preparationCommand(McpClientManager.McpServerConfig config,
                                    McpArtifactImporter.PreparedMcp prepared,
                                    Path workspace, String containerName,
                                    Path environmentFile) {
        SandboxUser sandboxUser = sandboxUser();
        List<String> command = new ArrayList<>();
        command.add(setting("MELON_DOCKER_COMMAND", "docker"));
        command.addAll(List.of("run", "--rm"));
        addRuntime(command);
        command.addAll(List.of(
                "--name", containerName,
                "--label", "melonpaw.runtime=mcp-prepare",
                "--network", setting("MELON_MCP_SANDBOX_NETWORK",
                        setting("MELON_SANDBOX_NETWORK", "bridge")),
                "--memory", setting("MELON_MCP_SANDBOX_MEMORY", "512m"),
                "--cpus", setting("MELON_MCP_SANDBOX_CPUS", "2"),
                "--pids-limit", setting("MELON_MCP_SANDBOX_PIDS", "256"),
                "--user", sandboxUser.value(),
                "--init", "--read-only",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=256m",
                "--tmpfs", sandboxUser.homeTmpfs(),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true"));
        Path hostWorkspace = SandboxWorkspacePathResolver.resolveHostPath(workspace);
        command.addAll(List.of(
                "--mount", workspaceMount(hostWorkspace),
                "--workdir", prepared.containerWorkingDirectory(),
                "--env-file", environmentFile.toString(),
                setting("MELON_MCP_SANDBOX_IMAGE",
                        setting("MELON_SANDBOX_IMAGE", "melonpaw-company-sandbox:latest")),
                "/bin/sh", "-eu", "-c", MCP_PREPARATION_SCRIPT, "melonpaw-mcp-prepare",
                prepared.preparationKind().id(),
                prepared.containerWorkingDirectory()));
        return command;
    }

    private void writePreparationMarker(Path marker, McpArtifactImporter.PreparedMcp prepared)
            throws IOException {
        Files.createDirectories(marker.getParent());
        Path temporary = Files.createTempFile(marker.getParent(), ".melon-runtime-", ".tmp");
        try {
            Files.writeString(temporary, prepared.contentHash() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String workspaceMount(Path hostWorkspace) {
        String source = hostWorkspace.toString();
        if (source.contains(",")) {
            throw new IllegalArgumentException("Docker workspace path cannot contain a comma");
        }
        return "type=bind,source=" + source + ",target=/workspace";
    }

    private void addRuntime(List<String> command) {
        String runtime = optionalSetting("MELON_MCP_SANDBOX_RUNTIME");
        if (runtime == null || runtime.isBlank()) runtime = optionalSetting("MELON_SANDBOX_RUNTIME");
        if (runtime == null || runtime.isBlank()) return;
        String value = runtime.trim();
        if (!value.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Docker sandbox runtime has an invalid name");
        }
        command.add("--runtime");
        command.add(value);
    }

    private void prepareCacheDirectories(Path workspace) throws IOException {
        if (workspace == null) return;
        Path cache = workspace.toAbsolutePath().normalize().resolve(".melon").resolve("mcp-cache");
        for (String name : List.of("npm", "uv", "pip")) Files.createDirectories(cache.resolve(name));
    }

    private String preparationOutput(Path file, McpClientManager.McpServerConfig config) {
        try {
            int limit = 16 * 1024;
            long size = Files.size(file);
            byte[] value;
            try (var input = Files.newInputStream(file)) {
                input.skipNBytes(Math.max(0L, size - limit));
                value = input.readNBytes(limit);
            }
            String result = new String(value, StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").strip();
            if (config.getEnv() != null) {
                for (String secret : config.getEnv().values()) {
                    if (secret != null && secret.length() >= 4) result = result.replace(secret, "***");
                }
            }
            return result.isBlank() ? "The sandbox returned no diagnostic output." : result;
        } catch (IOException e) {
            return "The sandbox diagnostic output could not be read.";
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private void removeContainer(String dockerCommand, String containerName) {
        try {
            Process cleanup = new ProcessBuilder(dockerCommand, "rm", "-f", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (IOException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LaunchedProcess startDocker(McpClientManager.McpServerConfig config, Path workspace)
            throws IOException {
        String containerName = containerName(config.getId());
        prepareCacheDirectories(workspace);
        Path environmentFile = writeEnvironmentFile(config, workspace);
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
        builder.environment().putAll(configuredEnvironment(config, false, workspace));
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

    private Map<String, String> configuredEnvironment(
            McpClientManager.McpServerConfig config, boolean sandboxed, Path workspace) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("npm_config_yes", "true");
        if (sandboxed) {
            String cacheRoot = workspace == null ? "/home/melon/.cache" : "/workspace/.melon/mcp-cache";
            environment.put("npm_config_cache", cacheRoot + "/npm");
            environment.put("npm_config_update_notifier", "false");
            environment.put("npm_config_fund", "false");
            environment.put("npm_config_audit", "false");
            environment.put("UV_CACHE_DIR", cacheRoot + "/uv");
            environment.put("UV_NO_PROGRESS", "1");
            environment.put("PIP_CACHE_DIR", cacheRoot + "/pip");
            environment.put("PIP_DISABLE_PIP_VERSION_CHECK", "1");
        }
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

    private Path writeEnvironmentFile(McpClientManager.McpServerConfig config, Path workspace)
            throws IOException {
        Path file = Files.createTempFile("melonpaw-mcp-env-", ".list");
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
            StringBuilder content = new StringBuilder();
            configuredEnvironment(config, true, workspace).forEach((name, value) ->
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

    private SandboxUser sandboxUser() {
        String value = setting("MELON_MCP_SANDBOX_USER", "10001:10001");
        if (!value.matches("[0-9]{1,10}(?::[0-9]{1,10})?")) {
            throw new IllegalArgumentException(
                    "MELON_MCP_SANDBOX_USER must use a numeric non-root uid[:gid]");
        }
        String[] parts = value.split(":", 2);
        long uid = Long.parseLong(parts[0]);
        long gid = Long.parseLong(parts.length == 2 ? parts[1] : parts[0]);
        long maxLinuxId = 4_294_967_294L;
        if (uid == 0 || gid == 0 || uid > maxLinuxId || gid > maxLinuxId) {
            throw new IllegalArgumentException(
                    "MELON_MCP_SANDBOX_USER must use a valid non-root uid and gid");
        }
        return new SandboxUser(value, uid, gid);
    }

    private String setting(String name, String fallback) {
        String value = optionalSetting(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private long positiveLongSetting(String name, long fallback) {
        String value = optionalSetting(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private String optionalSetting(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        if (value != null && !value.isBlank()) return value;
        if (configuredMcp == null) return null;
        return switch (name) {
            case "MELON_MCP_STDIO_MODE" -> configuredMcp.getStdioMode();
            case "MELON_DOCKER_COMMAND" -> configuredMcp.getDockerCommand();
            case "MELON_MCP_SANDBOX_IMAGE" -> configuredMcp.getSandboxImage();
            case "MELON_MCP_SANDBOX_NETWORK" -> configuredMcp.getSandboxNetwork();
            case "MELON_MCP_SANDBOX_RUNTIME" -> configuredMcp.getSandboxRuntime();
            case "MELON_MCP_SANDBOX_MEMORY" -> configuredMcp.getSandboxMemory();
            case "MELON_MCP_SANDBOX_CPUS" -> configuredMcp.getSandboxCpus();
            case "MELON_MCP_SANDBOX_PIDS" -> configuredMcp.getSandboxPids();
            case "MELON_MCP_SANDBOX_USER" -> configuredMcp.getSandboxUser();
            case "MELON_MCP_PREPARE_TIMEOUT_SECONDS" ->
                    Long.toString(configuredMcp.getPrepareTimeoutSeconds());
            default -> null;
        };
    }

    private record SandboxUser(String value, long uid, long gid) {
        private String homeTmpfs() {
            return "/home/melon:rw,exec,nosuid,nodev,size=256m,uid=" + uid
                    + ",gid=" + gid + ",mode=0700";
        }
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
