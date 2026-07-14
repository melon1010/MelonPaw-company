package com.melon.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理器. 对应 Python config.py 的 ConfigManager.
 * 负责加载/保存 YAML 配置, 合并默认值.
 */
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_DIR = ".melon";
    private static final String CONFIG_FILE = "config.yaml";

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private Path configPath;
    private MelonConfig config;
    private String defaultHomeDir = "~/.melon";

    public ConfigManager() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.configPath = Path.of(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
        this.config = mergeDefaults(new MelonConfig());
    }

    public void setConfigPath(Path configPath) {
        this.configPath = configPath;
    }

    public void setHomeDir(String homeDir) {
        this.defaultHomeDir = homeDir == null || homeDir.isBlank() ? "~/.melon" : homeDir;
        config.setHomeDir(homeDir);
    }

    /**
     * 加载配置. 如文件不存在则使用默认值并创建文件.
     */
    public MelonConfig load() {
        if (Files.exists(configPath)) {
            try {
                boolean migrateGlobalConfig = shouldMigrateGlobalConfig();
                config = yamlMapper.readValue(configPath.toFile(), MelonConfig.class);
                config = mergeDefaults(config);
                if (migrateGlobalConfig) {
                    loadLegacyWorkspaceAgentConfigs();
                }
                config = mergeDefaults(config);
                if (migrateGlobalConfig) {
                    save();
                }
                log.info("Config loaded from {}", configPath);
            } catch (IOException e) {
                log.error("Failed to load config, using defaults", e);
                config = mergeDefaults(new MelonConfig());
            }
        } else {
            log.info("Config file not found, creating with defaults at {}", configPath);
            config = mergeDefaults(new MelonConfig());
            save();
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private boolean shouldMigrateGlobalConfig() {
        try {
            Map<String, Object> root = yamlMapper.readValue(configPath.toFile(), Map.class);
            Object agents = root.get("agents");
            if (!(agents instanceof Map<?, ?> agentMap)) {
                return false;
            }
            for (Object value : agentMap.values()) {
                if (!(value instanceof Map<?, ?> item)) {
                    continue;
                }
                java.util.Set<String> keys = item.keySet().stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.toSet());
                if (java.util.Set.of("name", "description", "enabled", "workspace_dir").containsAll(keys)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to inspect global config for migration: {}", configPath, e);
        }
        return false;
    }

    /**
     * 原子保存配置.
     */
    public synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(CONFIG_FILE + ".tmp");
            yamlMapper.writeValue(tmp.toFile(), globalConfigPayload());
            try {
                Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            saveWorkspaceAgentConfigs();
            log.info("Config saved to {}", configPath);
        } catch (IOException e) {
            log.error("Failed to save config", e);
        }
    }

    /**
     * 重载配置.
     */
    public void reload() {
        load();
    }

    public MelonConfig getConfig() {
        return config;
    }

    public Path getConfigPath() {
        return configPath;
    }

    /**
     * 合并默认值. 对应 Python _default_builtin_tools() + _merge_default_tools.
     * 确保新工具出现在配置中, 旧条目规范化.
     */
    private MelonConfig mergeDefaults(MelonConfig loaded) {
        if (loaded.getHomeDir() == null || loaded.getHomeDir().isBlank()) {
            loaded.setHomeDir(defaultHomeDir);
        }
        if (loaded.getAgents() == null) {
            loaded.setAgents(Collections.singletonMap("default", new AgentConfig()));
        }
        for (var entry : loaded.getAgents().entrySet()) {
            AgentConfig ac = entry.getValue();
            if (ac.getTools() == null) ac.setTools(new ToolsConfig());
            mergeDefaultTools(ac.getTools());
            if (ac.getRunning() == null) ac.setRunning(new RunningConfig());
            if (ac.getContextCompact() == null) ac.setContextCompact(new ContextCompactConfig());
            if (ac.getLightContextConfig() == null) {
                LightContextConfig light = new LightContextConfig();
                light.setContextCompactConfig(ac.getContextCompact());
                ac.setLightContextConfig(light);
            }
            mergeLightContextDefaults(ac);
            if (ac.getMemoryManagerBackend() == null || ac.getMemoryManagerBackend().isBlank()) {
                ac.setMemoryManagerBackend("remelight");
            }
            if (ac.getPlanMode() == null) ac.setPlanMode(new PlanModeConfig());
            if (ac.getApproval() == null) ac.setApproval(new AgentConfig.ApprovalConfig());
            if (ac.getHeartbeat() == null) ac.setHeartbeat(new HeartbeatConfig());
            if (ac.getLanguage() == null || ac.getLanguage().isBlank()) ac.setLanguage("zh");
            if (ac.getTimezone() == null || ac.getTimezone().isBlank()) ac.setTimezone("UTC");
            if (ac.getFrontendRunningConfig() == null) ac.setFrontendRunningConfig(Map.of());
            mergeFrontendRunningConfig(ac);
        }
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private void loadLegacyWorkspaceAgentConfigs() {
        if (config.getAgents() == null || config.getAgents().isEmpty()) {
            return;
        }
        Map<String, AgentConfig> mergedAgents = new LinkedHashMap<>();
        for (var entry : config.getAgents().entrySet()) {
            String agentId = entry.getKey();
            AgentConfig globalAgent = entry.getValue();
            AgentConfig agent = globalAgent;
            Path agentJson = resolveWorkspaceDir(agentId).resolve("agent.json");
            if (Files.exists(agentJson)) {
                try {
                    Map<String, Object> raw = jsonMapper.readValue(agentJson.toFile(), Map.class);
                    Object node = raw.get("config");
                    if (!(node instanceof Map<?, ?>)) {
                        mergedAgents.put(agentId, globalAgent);
                        continue;
                    }
                    AgentConfig workspaceAgent = jsonMapper.convertValue(node, AgentConfig.class);
                    agent = mergeAgentIndex(globalAgent, workspaceAgent);
                } catch (Exception e) {
                    log.warn("Failed to load workspace agent config: {}", agentJson, e);
                }
            }
            mergedAgents.put(agentId, agent);
        }
        config.setAgents(mergedAgents);
    }

    private AgentConfig mergeAgentIndex(AgentConfig globalAgent, AgentConfig workspaceAgent) {
        if (workspaceAgent == null) {
            return globalAgent;
        }
        if (globalAgent != null) {
            workspaceAgent.setName(globalAgent.getName());
            workspaceAgent.setDescription(globalAgent.getDescription());
            workspaceAgent.setWorkspaceDir(globalAgent.getWorkspaceDir());
            workspaceAgent.setEnabled(globalAgent.isEnabled());
        }
        return workspaceAgent;
    }

    private void saveWorkspaceAgentConfigs() throws IOException {
        if (config.getAgents() == null) {
            return;
        }
        for (var entry : config.getAgents().entrySet()) {
            String agentId = entry.getKey();
            AgentConfig agent = entry.getValue();
            Path workspaceDir = resolveWorkspaceDir(agentId);
            Files.createDirectories(workspaceDir);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", 1);
            data.put("id", agentId);
            data.put("name", !isBlank(agent.getName()) ? agent.getName() : agentId);
            data.put("workspace_dir", workspaceDir.toString());
            data.put("active_model", agent.getActiveModel() != null ? agent.getActiveModel() : "");
            data.put("approval_level", agent.getApproval() != null ? agent.getApproval().getLevel() : "AUTO");
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(workspaceDir.resolve("agent.json").toFile(), data);
        }
    }

    private Map<String, Object> globalConfigPayload() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("server", config.getServer());
        root.put("home_dir", config.getHomeDir());
        if (config.getPaths() != null) {
            root.put("paths", config.getPaths());
        }
        if (config.getState() != null) {
            root.put("state", config.getState());
        }
        Map<String, Object> agents = new LinkedHashMap<>();
        if (config.getAgents() != null) {
            for (var entry : config.getAgents().entrySet()) {
                agents.put(entry.getKey(), entry.getValue());
            }
        }
        root.put("agents", agents);
        return root;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void mergeLightContextDefaults(AgentConfig agent) {
        LightContextConfig light = agent.getLightContextConfig();
        if (light.getStrategy() == null || light.getStrategy().isBlank()) light.setStrategy("scroll");
        if (!"native".equals(light.getStrategy()) && !"scroll".equals(light.getStrategy())) light.setStrategy("scroll");
        if (light.getDialogPath() == null || light.getDialogPath().isBlank()) light.setDialogPath("dialog");
        if (light.getTokenCountEstimateDivisor() <= 0) light.setTokenCountEstimateDivisor(4.0);
        if (light.getContextCompactConfig() == null) light.setContextCompactConfig(agent.getContextCompact());
        if (light.getToolResultPruningConfig() == null) light.setToolResultPruningConfig(new ToolResultPruningConfig());
        if (light.getScrollConfig() == null) light.setScrollConfig(new ScrollContextConfig());
        ScrollContextConfig scroll = light.getScrollConfig();
        if (scroll.getDbFilename() == null || scroll.getDbFilename().isBlank()) scroll.setDbFilename("history.db");
        if (scroll.getToolOutputTokenCap() <= 0) scroll.setToolOutputTokenCap(3000);
        if (scroll.getReplTimeoutS() <= 0) scroll.setReplTimeoutS(300);
        if (scroll.getHistoryRetentionDays() < 0) scroll.setHistoryRetentionDays(30);
        agent.setContextCompact(light.getContextCompactConfig());
    }

    private void mergeFrontendRunningConfig(AgentConfig agent) {
        Map<String, Object> frontend = agent.getFrontendRunningConfig();
        if (frontend == null || frontend.isEmpty()) return;
        if (frontend.get("light_context_config") instanceof Map<?, ?> lightRaw) {
            LightContextConfig light = jsonMapper.convertValue(lightRaw, LightContextConfig.class);
            if (!lightRaw.containsKey("context_compact_config")) {
                light.setContextCompactConfig(agent.getContextCompact());
            }
            agent.setLightContextConfig(light);
            mergeLightContextDefaults(agent);
        }
        if (frontend.get("memory_manager_backend") != null
                && (agent.getMemoryManagerBackend() == null || agent.getMemoryManagerBackend().isBlank()
                || "remelight".equals(agent.getMemoryManagerBackend()))) {
            agent.setMemoryManagerBackend(String.valueOf(frontend.get("memory_manager_backend")));
        }
    }

    private void mergeDefaultTools(ToolsConfig tools) {
        if (tools.getBuiltinTools() == null) {
            tools.setBuiltinTools(new LinkedHashMap<>(new ToolsConfig().getBuiltinTools()));
        } else {
            Map<String, BuiltinToolConfig> current = tools.getBuiltinTools();
            new ToolsConfig().getBuiltinTools().forEach(current::putIfAbsent);
            new ToolsConfig().getBuiltinTools().forEach((name, defaults) -> {
                BuiltinToolConfig tool = current.get(name);
                if (tool == null) return;
                if (tool.getName() == null || tool.getName().isBlank()) tool.setName(name);
                if (tool.getDescription() == null || tool.getDescription().isBlank()) {
                    tool.setDescription(defaults.getDescription());
                }
                if (tool.getIcon() == null || tool.getIcon().isBlank()) tool.setIcon(defaults.getIcon());
                if (!defaults.isDisplayToUser()) tool.setDisplayToUser(false);
            });
        }
        Map<String, BuiltinToolConfig> current = tools.getBuiltinTools();
        migrateTool(current, "execute", "execute_shell_command");
        migrateTool(current, "grep_files", "grep_search");
        migrateTool(current, "glob_files", "glob_search");
        List<String> removedTools = List.of(
                "desktop_screenshot", "delegate_external_agent", "list_agents",
                "chat_with_agent", "submit_to_agent", "check_agent_task", "spawn_subagent",
                "materialize_skill");
        removedTools.forEach(current::remove);
        Map<String, Boolean> asyncTools = new LinkedHashMap<>(
                tools.getAsyncExecutionTools() != null ? tools.getAsyncExecutionTools() : Map.of());
        removedTools.forEach(asyncTools::remove);
        tools.setAsyncExecutionTools(asyncTools);
        for (String legacy : java.util.List.of(
                "execute", "grep_files", "glob_files", "list_files",
                "append_file", "memory_search", "memory_get",
                "session_search", "session_list", "session_history",
                "skill_manage", "propose_skill", "load_skill_through_path",
                "reset_equipped_tools",
                "plan_enter", "plan_write", "plan_exit",
                "task", "task_output", "task_list", "task_cancel")) {
            BuiltinToolConfig tool = current.get(legacy);
            if (tool != null) {
                tool.setEnabled(false);
                tool.setDisplayToUser(false);
            }
        }
    }

    private void migrateTool(java.util.Map<String, BuiltinToolConfig> tools, String from, String to) {
        BuiltinToolConfig source = tools.get(from);
        BuiltinToolConfig target = tools.get(to);
        if (source == null || target == null) return;
        target.setEnabled(source.isEnabled());
        target.setAsyncExecution(source.isAsyncExecution());
        if ((target.getDescription() == null || target.getDescription().isBlank())
                && source.getDescription() != null) {
            target.setDescription(source.getDescription());
        }
    }

    public Path resolveHomeDir() {
        return expandPath(config.getHomeDir() == null || config.getHomeDir().isBlank()
                ? "~/.melon"
                : config.getHomeDir());
    }

    public Path resolveWorkspaceDir(String agentId) {
        String id = normalizeAgentId(agentId);
        AgentConfig agent = config.getAgents().get(id);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown agent: " + id);
        }
        if (agent != null && agent.getWorkspaceDir() != null && !agent.getWorkspaceDir().isBlank()) {
            return expandPath(agent.getWorkspaceDir());
        }
        Path root = resolveWorkspaceRootDir().toAbsolutePath().normalize();
        Path workspace = root.resolve(id).normalize();
        if (!workspace.startsWith(root) || workspace.equals(root)) {
            throw new IllegalArgumentException("Invalid agent workspace: " + id);
        }
        return workspace;
    }

    private String normalizeAgentId(String agentId) {
        String id = agentId == null || agentId.isBlank() ? "default" : agentId.trim();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("Invalid agent id");
        }
        return id;
    }

    public Path resolveWorkspaceRootDir() {
        if (config.getPaths() != null
                && config.getPaths().getWorkspaceRoot() != null
                && !config.getPaths().getWorkspaceRoot().isBlank()) {
            return expandPath(config.getPaths().getWorkspaceRoot()).normalize();
        }
        return resolveHomeDir().resolve("workspaces").normalize();
    }

    public Path resolveSkillPoolDir() {
        if (config.getPaths() != null
                && config.getPaths().getSkillPoolDir() != null
                && !config.getPaths().getSkillPoolDir().isBlank()) {
            return expandPath(config.getPaths().getSkillPoolDir());
        }
        return resolveHomeDir().resolve("skill_pool").normalize();
    }

    public Path resolveStateDir() {
        if (config.getState() != null
                && config.getState().getBaseDir() != null
                && !config.getState().getBaseDir().isBlank()) {
            return expandPath(config.getState().getBaseDir());
        }
        return resolveHomeDir().resolve("state").normalize();
    }

    public String stateStoreType() {
        return config.getState() == null || config.getState().getStore() == null || config.getState().getStore().isBlank()
                ? "json_file"
                : config.getState().getStore();
    }

    private Path expandPath(String path) {
        String value = path;
        if (value.startsWith("~")) {
            value = System.getProperty("user.home") + value.substring(1);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
