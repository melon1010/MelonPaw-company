package com.melon.core.agent;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.core.state.AgentStateStore;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ContextCompactConfig;
import com.melon.core.config.LightContextConfig;
import com.melon.core.config.ToolResultPruningConfig;
import com.melon.core.middleware.*;
import com.melon.core.provider.ProviderManager;
import com.melon.core.provider.LlmRateLimitedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HarnessAgent 构建工厂. 对应 Python react_agent.py 的 MelonAgent.__init__ + _create_toolkit + _build_sys_prompt + _register_hooks.
 * 构建配置完整的 HarnessAgent 单例, 而非每请求新建.
 */
public class MelonAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(MelonAgentFactory.class);
    private final TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback;
    private final List<ToolkitContributor> toolkitContributors;
    private final ProviderManager providerManager;
    private final String configuredExecutionSandboxMode;

    public MelonAgentFactory() {
        this(null, List.of(), null, null);
    }

    public MelonAgentFactory(TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback) {
        this(tokenUsageCallback, List.of(), null, null);
    }

    public MelonAgentFactory(TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors) {
        this(tokenUsageCallback, toolkitContributors, null, null);
    }

    public MelonAgentFactory(TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager) {
        this(tokenUsageCallback, toolkitContributors, providerManager, null);
    }

    public MelonAgentFactory(TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager,
                             String executionSandboxMode) {
        this.tokenUsageCallback = tokenUsageCallback;
        this.toolkitContributors = toolkitContributors != null ? List.copyOf(toolkitContributors) : List.of();
        this.providerManager = providerManager;
        this.configuredExecutionSandboxMode = executionSandboxMode == null || executionSandboxMode.isBlank()
                ? null
                : normalizeExecutionSandboxMode(executionSandboxMode);
        if (this.configuredExecutionSandboxMode != null) {
            log.info("Agent execution sandbox mode configured: {}", this.configuredExecutionSandboxMode);
        }
    }

    public MelonAgentFactory(MultiAgentManager ignoredAgentManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager) {
        this(tokenUsageCallback, toolkitContributors, providerManager, null);
    }

    public MelonAgentFactory(MultiAgentManager ignoredAgentManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager,
                             String executionSandboxMode) {
        this(tokenUsageCallback, toolkitContributors, providerManager, executionSandboxMode);
    }

    /**
     * 构建一个配置完整的 HarnessAgent 实例.
     */
    public HarnessAgent create(String agentId, AgentConfig agentConfig, Path workspaceDir,
                                AgentStateStore stateStore) {
        String activeModel = agentConfig.getActiveModel();
        if (activeModel == null || activeModel.isBlank()) {
            throw new IllegalStateException("Model not configured");
        }
        log.info("Building HarnessAgent: name={}, model={}, workspace={}",
                agentConfig.getName(), activeModel, workspaceDir);

        // 1. 构建中间件链 (对应 Python Mixin + hooks)
        List<MiddlewareBase> middlewares = buildMiddlewares(agentId, agentConfig);

        // Create one model instance before middleware construction so compaction can use the
        // provider-reported context window and all model calls share the Agent rate limiter.
        Model primaryModel = providerManager != null
                ? LlmRateLimitedModel.wrap(providerManager.createModel(activeModel), agentId, agentConfig.getRunning())
                : null;

        // 2. 构建压缩配置
        CompactionConfig compaction = buildCompactionConfig(agentId, agentConfig, primaryModel);
        ToolResultEvictionConfig toolResultEviction = buildToolResultEvictionConfig(agentConfig);

        // 3. 构建记忆配置
        MemoryConfig memoryConfig = buildMemoryConfig(agentConfig);

        // 4. 注册 melonPaw 命名工具，避免 Harness 默认 execute/grep_files/glob_files 名称进入前端历史。
        Toolkit toolkit = buildToolkit(agentId, agentConfig, workspaceDir);

        // 4. 构建 HarnessAgent
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentConfig.getName())
                .agentId(agentId)
                .toolkit(toolkit)
                .workspace(workspaceDir);
        String sandboxMode = executionSandboxMode();
        if ("local".equals(sandboxMode)) {
            log.warn("Agent {} uses local shell execution. This is a development-only mode; "
                    + "enterprise deployments must use DockerFilesystemSpec.", agentId);
            builder.filesystem(localFilesystem(workspaceDir, agentConfig));
        } else if ("docker".equals(sandboxMode)) {
            builder.filesystem(dockerFilesystem(workspaceDir));
        } else {
            throw new IllegalStateException("Unsupported execution sandbox mode '" + sandboxMode
                    + "'. Supported modes: local, docker");
        }
        builder
                .toolsConfig(buildHarnessToolsConfig(agentConfig))
                .middlewares(middlewares)
                .toolResultEviction(toolResultEviction)
                .memory(memoryConfig)
                .stateStore(stateStore)
                .maxIters(effectiveMaxIters(agentConfig))
                .maxRetries(agentConfig.getRunning().isLlmRetryEnabled() ? agentConfig.getRunning().getLlmMaxRetries() : 0)
                .modelExecutionConfig(modelExecutionConfig(agentConfig))
                .toolExecutionConfig(toolExecutionConfig(agentConfig))
                .permissionContext(buildPermissionContext(agentConfig))
                .enableMetaTool(true)
                .enableSkillManageTool(SkillManageConfig.builder()
                        .autoPromote(true)
                        .securityScan(true)
                        .draftsDir("skills/_drafts")
                        .mainDir("skills")
                        .build())
                .enableTaskList(agentConfig.getRunning().isTaskListEnabled());
        if (agentConfig.getContextCompact().isEnabled()) {
            builder.compaction(compaction);
        } else {
            builder.disableCompaction();
        }
        if ("none".equalsIgnoreCase(agentConfig.getMemoryManagerBackend())) {
            builder.disableMemoryTools().disableMemoryHooks();
        }
        addContextFiles(builder, agentConfig);
        if (primaryModel != null) {
            builder.model(primaryModel);
        } else {
            builder.model(activeModel);
        }
        builder.subagentFactory("general-purpose", ignored ->
                createRestrictedSubagent(agentId, agentConfig, workspaceDir, "researcher"));
        builder.subagentFactory("researcher", ignored ->
                createRestrictedSubagent(agentId, agentConfig, workspaceDir, "researcher"));
        builder.subagentFactory("executor", ignored ->
                createRestrictedSubagent(agentId, agentConfig, workspaceDir, "executor"));
        builder.subagentFactory("reviewer", ignored ->
                createRestrictedSubagent(agentId, agentConfig, workspaceDir, "reviewer"));
        // Keep mission workers fixed to these role-scoped factories. Runtime workspace
        // declarations must not replace them with a worker that inherits coordinator tools.
        builder.disableDynamicSubagents();

        if (agentConfig.getPlanMode().isEnabled()) {
            builder.enablePlanMode(true);
        }

        if (agentConfig.getRunning().getFallbackModel() != null) {
            builder.fallbackModel(agentConfig.getRunning().getFallbackModel());
        }

        HarnessAgent agent = builder.build();
        SubagentTaskCoordinator.install(agent, workspaceDir);
        if (isEnabled(agentConfig, "execute_shell_command")) {
            installOptionalShellTool(agent);
        }
        return agent;
    }

    /**
     * Mission workers can perform ordinary isolated workspace work, but risky capabilities remain
     * on the coordinator so AgentRunner can apply the interactive risk policy.
     */
    private HarnessAgent createRestrictedSubagent(String parentAgentId, AgentConfig config,
                                                  Path workspaceDir, String role) {
        String normalizedRole = role == null || role.isBlank() ? "researcher" : role;
        boolean shellEnabled = "executor".equalsIgnoreCase(normalizedRole);
        Toolkit subagentToolkit = new Toolkit();
        registerIfEnabled(subagentToolkit, config, "web_search", "com.melon.tools.web.WebSearchTool");
        registerIfEnabled(subagentToolkit, config, "web_fetch", "com.melon.tools.web.WebFetchTool");
        HarnessAgent.Builder subagent = HarnessAgent.builder()
                .name(normalizedRole + "-subagent")
                .agentId(parentAgentId + "-worker")
                .description("Isolated " + normalizedRole + " worker with role-scoped workspace tools")
                .workspace(workspaceDir)
                .maxIters(subagentMaxIters(config, normalizedRole))
                .sysPrompt(subagentSystemPrompt(normalizedRole))
                .toolkit(subagentToolkit)
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .disableSubagents()
                .disableSessionPersistence();
        io.agentscope.harness.agent.tools.ToolsConfig roleTools = new io.agentscope.harness.agent.tools.ToolsConfig();
        List<String> allowedTools = new ArrayList<>("executor".equalsIgnoreCase(normalizedRole)
                ? List.of("read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files")
                : List.of("read_file", "grep_files", "glob_files", "list_files"));
        allowedTools.add("web_search");
        allowedTools.add("web_fetch");
        if (shellEnabled) {
            allowedTools.add("execute_shell_command");
        }
        roleTools.setAllow(List.copyOf(allowedTools));
        subagent.toolsConfig(roleTools);
        String sandboxMode = executionSandboxMode();
        if ("local".equals(sandboxMode)) {
            subagent.filesystem(localFilesystem(workspaceDir, config));
        } else if ("docker".equals(sandboxMode)) {
            subagent.filesystem(dockerFilesystem(workspaceDir));
        } else {
            throw new IllegalStateException("Unsupported execution sandbox mode '" + sandboxMode + "'");
        }
        if (providerManager != null) {
            subagent.model(LlmRateLimitedModel.wrap(
                    providerManager.createModel(config.getActiveModel()), parentAgentId, config.getRunning()));
        } else {
            subagent.model(config.getActiveModel());
        }
        addContextFiles(subagent, config);
        HarnessAgent agent = subagent.build();
        if (shellEnabled) {
            installOptionalShellTool(agent);
        }
        return agent;
    }

    private String subagentSystemPrompt(String role) {
        String common = "You are the " + role + " role in a single-level enterprise delegation. "
                + "Stay within the assigned workspace and return concise evidence for the coordinator. "
                + "Prefer web_search, web_fetch, and file tools; use execute_shell_command only for actual system commands. ";
        if ("researcher".equalsIgnoreCase(role)) {
            return common + "For every material claim, identify the source URL and quote or summarize the supporting evidence. "
                    + "Separate successfully inspected sources from failed or inaccessible sources. Never describe a failed fetch as verified. "
                    + "Call out conflicting evidence and uncertain conclusions explicitly. Stop when 6-8 independent sources cover the task; "
                    + "do not repeat equivalent searches.";
        }
        if ("reviewer".equalsIgnoreCase(role)) {
            return common + "Act as an adversarial verifier, not a copy editor. Check internal contradictions, numeric reasoning, "
                    + "source-to-claim support, and whether failed sources were incorrectly counted as verification. "
                    + "Review the supplied worker handoff before using tools. Do not scan chat or session history and do not repeat broad research; "
                    + "use targeted lookup only for a disputed cited claim. Return concrete conflicts and unsupported claims before any corrected synthesis.";
        }
        return common + "Report completed actions, observable results, and any failed verification step without hiding it.";
    }

    /** Keeps the Harness shell contract compatible with skills that omit optional cwd/timeout. */
    private void installOptionalShellTool(HarnessAgent agent) {
        if (agent == null || agent.getWorkspaceManager() == null
                || !(agent.getWorkspaceManager().getFilesystem() instanceof AbstractSandboxFilesystem sandbox)) {
            return;
        }
        Toolkit toolkit = agent.getToolkit();
        toolkit.removeTool("execute");
        toolkit.removeTool("execute_shell_command");
        toolkit.registerTool(new OptionalShellTool(new ShellExecuteTool(sandbox),
                agent.getWorkspaceManager().getWorkspace()));
    }

    private static final class OptionalShellTool {
        private final ShellExecuteTool delegate;
        private final Path workspace;

        private OptionalShellTool(ShellExecuteTool delegate, Path workspace) {
            this.delegate = delegate;
            this.workspace = workspace != null ? workspace.toAbsolutePath().normalize() : null;
        }

        private String execute(RuntimeContext runtimeContext, String command,
                               String workingDirectory, Integer timeout) {
            return delegate.execute(runtimeContext, command, normalizeWorkingDirectory(workingDirectory),
                    timeout != null ? timeout : 30);
        }

        @Tool(name = "execute_shell_command", description = "Execute an actual system command in the isolated Agent workspace. "
                + "Do not use this tool for web browsing, HTTP requests, file reading, or text search when specialized tools exist.")
        public String executeShellCommand(RuntimeContext runtimeContext,
                                          @ToolParam(name = "command", description = "Shell command to execute") String command,
                                          @ToolParam(name = "working_directory", required = false,
                                                  description = "Relative workspace directory; defaults to the workspace root") String workingDirectory,
                                          @ToolParam(name = "timeout", required = false,
                                                  description = "Timeout in seconds; defaults to 30") Integer timeout) {
            return execute(runtimeContext, command, workingDirectory, timeout);
        }

        private String normalizeWorkingDirectory(String value) {
            if (value == null || value.isBlank() || workspace == null) return value;
            try {
                Path requested = Path.of(value).toAbsolutePath().normalize();
                if (requested.startsWith(workspace)) {
                    return workspace.relativize(requested).toString();
                }
            } catch (RuntimeException ignored) {
                // Let the Harness validator return its normal path error.
            }
            return value;
        }
    }

    private Toolkit buildToolkit(String agentId, AgentConfig config, Path workspaceDir) {
        Toolkit toolkit = new Toolkit();
        registerIfEnabled(toolkit, config, "web_search", "com.melon.tools.web.WebSearchTool");
        registerIfEnabled(toolkit, config, "web_fetch", "com.melon.tools.web.WebFetchTool");
        registerIfEnabled(toolkit, config, "view_image", "com.melon.tools.media.ViewImageTool", workspaceDir);
        registerIfEnabled(toolkit, config, "view_video", "com.melon.tools.media.ViewVideoTool", workspaceDir);
        registerIfEnabled(toolkit, config, "get_current_time", "com.melon.tools.util.GetCurrentTimeTool",
                config.getTimezone());
        registerIfEnabled(toolkit, config, "set_user_timezone", "com.melon.tools.util.SetUserTimezoneTool");
        registerIfEnabled(toolkit, config, "get_token_usage", "com.melon.tools.util.GetTokenUsageTool");
        registerIfEnabled(toolkit, config, "send_file_to_user", "com.melon.tools.util.SendFileToUserTool", workspaceDir.toString());
        for (ToolkitContributor contributor : toolkitContributors) {
            try {
                contributor.contribute(agentId, config, workspaceDir, toolkit);
            } catch (Exception e) {
                log.warn("Toolkit contributor {} failed for agent {}", contributor.getClass().getName(), agentId, e);
            }
        }
        log.info("melonPaw toolkit registered tools: {}", toolkit.getToolNames());
        return toolkit;
    }

    private DockerFilesystemSpec dockerFilesystem(Path workspaceDir) {
        String image = envOr("MELON_SANDBOX_IMAGE", "melonpaw-company-sandbox:latest");
        DockerFilesystemSpec spec = new DockerFilesystemSpec()
                .image(image)
                .workspaceRoot("/workspace")
                .memorySizeBytes(512L * 1024L * 1024L)
                .cpuCount(2L)
                // The sandbox must be able to fetch public documentation and skill assets.
                // Operators can set MELON_SANDBOX_NETWORK=none or provide a restricted
                // Docker network when outbound access is not allowed by policy.
                .network(envOr("MELON_SANDBOX_NETWORK", "bridge"))
                .additionalRunArgs(
                        "--cap-drop=ALL",
                        "--security-opt=no-new-privileges:true",
                        "--pids-limit=256");
        spec.isolationScope(IsolationScope.USER);
        // Keep the Web workspace and the Agent workspace consistent. Only the already validated
        // per-user workspace is mounted; the project root and all other users remain outside.
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/workspace");
        BindMountEntry workspaceMount = new BindMountEntry();
        workspaceMount.setHostPath(workspaceDir.toAbsolutePath().normalize().toString());
        workspaceMount.setReadOnly(false);
        workspaceSpec.getEntries().put(".", workspaceMount);
        spec.workspaceSpec(workspaceSpec);
        spec.workspaceProjectionEnabled(false);
        return spec;
    }

    private CrossPlatformLocalFilesystemSpec localFilesystem(
            Path workspaceDir, AgentConfig agentConfig) {
        CrossPlatformLocalFilesystemSpec spec = new CrossPlatformLocalFilesystemSpec()
                .project(workspaceDir)
                .mode(LocalFsMode.SANDBOXED)
                .inheritEnv(false)
                .executeTimeoutSeconds(shellTimeoutSeconds(agentConfig));
        spec.projectWritable(true);
        // The application already assigns a dedicated workspace per personal Agent.
        // Avoid a second user-id directory that would separate file tools from shell.
        spec.isolationScope(IsolationScope.AGENT);
        for (String name : localShellEnvironmentAllowlist()) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                spec.env(name, value);
            }
        }
        return spec;
    }

    private List<String> localShellEnvironmentAllowlist() {
        return List.of(
                "PATH", "Path", "PATHEXT", "SystemRoot", "ComSpec",
                "HOME", "USERPROFILE", "TEMP", "TMP",
                "LANG", "LC_ALL", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
                "http_proxy", "https_proxy", "no_proxy");
    }

    private String executionSandboxMode() {
        String value = configuredExecutionSandboxMode;
        if (value == null || value.isBlank()) {
            value = envOr("MELON_EXECUTION_SANDBOX", "docker");
        }
        return normalizeExecutionSandboxMode(value);
    }

    private static String normalizeExecutionSandboxMode(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!"local".equals(normalized) && !"docker".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported execution sandbox mode '" + value
                    + "'. Supported modes: local, docker");
        }
        return normalized;
    }

    private String envOr(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void registerIfEnabled(Toolkit toolkit, AgentConfig config, String toolName, String className, Object... args) {
        if (!isEnabled(config, toolName)) return;
        try {
            Class<?> type = Class.forName(className);
            Object tool = newInstance(type, args);
            toolkit.registerTool(tool);
        } catch (ClassNotFoundException e) {
            log.debug("Tool implementation not on classpath: {}", className);
        } catch (Exception e) {
            log.warn("Failed to register tool {} ({})", toolName, className, e);
        }
    }

    private Object newInstance(Class<?> type, Object... args) throws Exception {
        for (Constructor<?> ctor : type.getConstructors()) {
            Class<?>[] parameterTypes = ctor.getParameterTypes();
            if (parameterTypes.length != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (args[i] != null && !wrap(parameterTypes[i]).isInstance(args[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return ctor.newInstance(args);
        }
        return type.getDeclaredConstructor().newInstance();
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    private boolean isEnabled(AgentConfig config, String toolName) {
        if (config.getTools() == null || config.getTools().getBuiltinTools() == null) return true;
        var tool = config.getTools().getBuiltinTools().get(toolName);
        return tool == null || tool.isEnabled();
    }

    private ToolsConfig buildHarnessToolsConfig(AgentConfig config) {
        ToolsConfig tools = new ToolsConfig();
        List<String> denied = new ArrayList<>();
        denyOfficialToolWhenDisabled(config, denied, "execute_shell_command", "execute");
        denyOfficialToolWhenDisabled(config, denied, "read_file", "read_file");
        denyOfficialToolWhenDisabled(config, denied, "write_file", "write_file");
        denyOfficialToolWhenDisabled(config, denied, "edit_file", "edit_file");
        denyOfficialToolWhenDisabled(config, denied, "grep_search", "grep_files");
        denyOfficialToolWhenDisabled(config, denied, "glob_search", "glob_files");
        if (!isEnabled(config, "read_file")) {
            denied.add("list_files");
        }
        tools.setDeny(List.copyOf(new LinkedHashSet<>(denied)));
        return tools;
    }

    private void denyOfficialToolWhenDisabled(
            AgentConfig config, List<String> denied, String configuredName, String officialName) {
        if (!isEnabled(config, configuredName)) {
            denied.add(officialName);
        }
    }

    private void addContextFiles(HarnessAgent.Builder builder, AgentConfig config) {
        if (config.getSystemPromptFiles() == null) return;
        for (String fileName : config.getSystemPromptFiles()) {
            if (fileName == null || fileName.isBlank()
                    || "AGENTS.md".equalsIgnoreCase(fileName)
                    || "MEMORY.md".equalsIgnoreCase(fileName)) {
                continue;
            }
            Path relative = Path.of(fileName).normalize();
            if (relative.isAbsolute() || relative.getNameCount() != 1 || "..".equals(relative.toString())) {
                log.warn("Ignoring unsafe workspace context file: {}", fileName);
                continue;
            }
            builder.additionalContextFile(relative.toString().replace('\\', '/'));
        }
    }

    /**
     * 构建中间件链. 对应 Python 的 Mixin 继承链 + LightContextManager hooks.
     */
    private List<MiddlewareBase> buildMiddlewares(String agentId, AgentConfig config) {
        List<MiddlewareBase> list = new ArrayList<>();

        // Workspace context, memory and skills are provided by HarnessAgent.
        list.add(new ToolRoutingMiddleware());
        list.add(new ToolFailureGuardMiddleware());
        list.add(new MediaFilterMiddleware());

        if (config.getRunning().isAutoContinueOnTextOnly()) {
            list.add(new AutoContinueMiddleware());
        }

        list.add(new TokenRecordingMiddleware((ignoredAgentName, sessionId, modelName, usage, latencyMs) -> {
            if (tokenUsageCallback != null) {
                String modelId = config.getActiveModel() != null && !config.getActiveModel().isBlank()
                        ? config.getActiveModel()
                        : modelName;
                tokenUsageCallback.record(agentId, sessionId, modelId, usage, latencyMs);
            }
        }));

        // HarnessAgent 会注册官方 file-backed memory_search/memory_get/session_search 工具。
        // Java 侧尚未接入 Python 的 ReMeLight/ADBPG 语义记忆后端，所以这里不再注入自定义假 memory_search。

        return list;
    }

    private ExecutionConfig modelExecutionConfig(AgentConfig config) {
        int attempts = config.getRunning().isLlmRetryEnabled()
                ? Math.max(1, config.getRunning().getLlmMaxRetries() + 1)
                : 1;
        return ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .maxAttempts(attempts)
                .initialBackoff(Duration.ofMillis(Math.max(100, Math.round(config.getRunning().getLlmBackoffBase() * 1000))))
                .maxBackoff(Duration.ofMillis(Math.max(500, Math.round(config.getRunning().getLlmBackoffCap() * 1000))))
                .backoffMultiplier(2.0)
                .retryOn(config.getRunning().isLlmRetryEnabled() ? ExecutionConfig.RETRYABLE_ERRORS : ignored -> false)
                .build();
    }

    private ExecutionConfig toolExecutionConfig(AgentConfig config) {
        // Shell and MCP tools enforce their own per-call limits. The Harness-level timeout is a
        // final safety ceiling shared by every tool, so binding it to shell_command_timeout would
        // also interrupt task_wait and subagent orchestration after the shell-specific duration.
        return ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(11))
                .maxAttempts(1)
                .build();
    }

    private PermissionContextState buildPermissionContext(AgentConfig config) {
        String level = config.getApproval() != null ? config.getApproval().getLevel() : "AUTO";
        if ("OFF".equalsIgnoreCase(level)) {
            return PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
        }
        boolean strict = "STRICT".equalsIgnoreCase(level);
        boolean riskBased = "RISK_BASED".equalsIgnoreCase(level)
                || "AUTO".equalsIgnoreCase(level)
                || "SMART".equalsIgnoreCase(level);
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(strict ? PermissionMode.DEFAULT : PermissionMode.BYPASS);
        if (strict) {
            for (String tool : strictApprovalTools()) {
                builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "melonpaw-java"));
            }
        } else {
            if (riskBased) {
                for (String tool : riskCandidateTools()) {
                    builder.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "melonpaw-java"));
                }
            }
        }
        return builder.build();
    }

    private List<String> lifecycleApprovalTools() {
        return List.of(
                "skill_manage",
                "propose_skill",
                "task_cancel",
                "plan_exit"
        );
    }

    private List<String> smartApprovalTools() {
        return List.of(
                "execute",
                "execute_shell_command",
                "write_file",
                "edit_file"
        );
    }

    private List<String> riskCandidateTools() {
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        tools.addAll(smartApprovalTools());
        tools.addAll(lifecycleApprovalTools());
        tools.add("agent_spawn");
        return List.copyOf(tools);
    }

    private List<String> strictApprovalTools() {
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        tools.addAll(List.of("execute", "execute_shell_command", "write_file", "edit_file", "read_file",
                "grep_files", "glob_files", "list_files"));
        tools.addAll(lifecycleApprovalTools());
        tools.addAll(smartApprovalTools());
        tools.addAll(List.of("memory_search", "memory_get", "session_search", "create_cron_job"));
        return List.copyOf(tools);
    }

    /**
     * 构建压缩配置. 对应 Python ContextCompactConfig.
     */
    private CompactionConfig buildCompactionConfig(String agentId, AgentConfig config, Model model) {
        ContextCompactConfig py = config.getContextCompact();
        int maxInputTokens = model != null && model.getContextWindowSize() > 0
                ? model.getContextWindowSize()
                : 128 * 1024;
        int triggerTokens = (int) Math.max(1, Math.round(maxInputTokens * py.getCompactThresholdRatio()));
        int keepTokens = (int) Math.max(1, Math.round(maxInputTokens * py.getReserveThresholdRatio()));
        CompactionConfig.Builder builder = CompactionConfig.builder()
                .triggerMessages(py.getTriggerMessages())
                .triggerTokens(triggerTokens)
                .keepMessages(py.getKeepMessages())
                .keepTokens(keepTokens)
                .flushBeforeCompact(true)
                .offloadBeforeCompact(true);

        if (py.getSummaryModel() != null && !py.getSummaryModel().isBlank()) {
            if (providerManager != null) {
                try {
                    builder.model(LlmRateLimitedModel.wrap(
                            providerManager.createModel(py.getSummaryModel()), agentId, config.getRunning()));
                } catch (RuntimeException e) {
                    log.warn("Unable to configure the dedicated compaction model {}; using the primary model",
                            py.getSummaryModel(), e);
                }
            } else {
                builder.model(py.getSummaryModel());
            }
        }

        // 参数预截断 (Java 独有增强)
        builder.truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                .maxArgLength(2000)
                .truncationText("... [truncated] ...")
                .build());

        return builder.build();
    }

    /**
     * 构建单条工具结果驱逐配置. 对应 AgentScope 2.0 官方 ToolResultEvictionMiddleware.
     */
    private ToolResultEvictionConfig buildToolResultEvictionConfig(AgentConfig config) {
        ToolResultEvictionConfig.Builder builder = ToolResultEvictionConfig.builder();

        LightContextConfig light = config.getLightContextConfig();
        ToolResultPruningConfig pruning = light != null ? light.getToolResultPruningConfig() : null;
        if (pruning == null || !pruning.isEnabled()) {
            // Preserve AgentScope's hard 80K safety threshold when aggressive pruning is disabled.
            return ToolResultEvictionConfig.defaults();
        }
        int maxChars = firstPositive(pruning.getPruningOldMsgMaxBytes(), pruning.getPruningRecentMsgMaxBytes());
        if (maxChars > 0) {
            builder.maxResultChars(maxChars);
            builder.previewChars(Math.min(ToolResultEvictionConfig.DEFAULT_PREVIEW_CHARS,
                    Math.max(500, maxChars / 10)));
        }

        Set<String> excluded = new LinkedHashSet<>(ToolResultEvictionConfig.DEFAULT_EXCLUDED_TOOLS);
        excluded.add("grep_search");
        excluded.add("glob_search");
        if (pruning != null && pruning.getExemptToolNames() != null) {
            excluded.addAll(pruning.getExemptToolNames());
        }
        builder.excludedToolNames(excluded);
        return builder.build();
    }

    private int firstPositive(int first, int second) {
        if (first > 0) return first;
        if (second > 0) return second;
        return 0;
    }

    /**
     * 构建记忆配置.
     */
    private MemoryConfig buildMemoryConfig(AgentConfig config) {
        return MemoryConfig.builder()
                .flushTrigger(MemoryConfig.FlushTrigger.always())
                .consolidationMaxTokens(4000)
                .consolidationMinGap(java.time.Duration.ofMinutes(30))
                .dailyFileRetentionDays(90)
                .sessionRetentionDays(180)
                .build();
    }

    private int shellTimeoutSeconds(AgentConfig config) {
        int seconds = (int) Math.ceil(config.getRunning().getShellCommandTimeout());
        return Math.max(1, seconds);
    }

    private int effectiveMaxIters(AgentConfig config) {
        return config != null && config.getRunning() != null
                ? config.getRunning().getMaxIters()
                : com.melon.core.config.RunningConfig.DEFAULT_MAX_ITERS;
    }

    private int subagentMaxIters(AgentConfig config, String role) {
        int configured = effectiveMaxIters(config);
        if ("reviewer".equalsIgnoreCase(role)) return Math.min(configured, 6);
        if ("researcher".equalsIgnoreCase(role)) return Math.min(configured, 8);
        return configured;
    }

}
