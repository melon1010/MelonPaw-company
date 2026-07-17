package com.melon.core.agent;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.middleware.TokenRecordingMiddleware;
import com.melon.core.provider.ProviderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * 多 Agent 管理器. 对应 Python app/multi_agent_manager.py.
 * 管理 HarnessAgent 单例, 按配置创建/重载.
 */
public class MultiAgentManager {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentManager.class);

    private final Map<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final MelonAgentFactory agentFactory;
    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final ProviderManager providerManager;
    private AgentStateStore stateStore;

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager) {
        this(configManager, workspaceManager, null);
    }

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback) {
        this(configManager, workspaceManager, tokenUsageCallback, List.of());
    }

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors) {
        this(configManager, workspaceManager, tokenUsageCallback, toolkitContributors, null);
    }

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager) {
        this(configManager, workspaceManager, tokenUsageCallback, toolkitContributors, providerManager, null);
    }

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager,
                             AgentStateStore stateStore) {
        this(configManager, workspaceManager, tokenUsageCallback, toolkitContributors,
                providerManager, stateStore, null);
    }

    public MultiAgentManager(ConfigManager configManager, WorkspaceManager workspaceManager,
                             TokenRecordingMiddleware.TokenUsageCallback tokenUsageCallback,
                             List<ToolkitContributor> toolkitContributors,
                             ProviderManager providerManager,
                             AgentStateStore stateStore,
                             String executionSandboxMode) {
        this.configManager = configManager;
        this.providerManager = providerManager;
        this.agentFactory = new MelonAgentFactory(this, tokenUsageCallback, toolkitContributors,
                providerManager, executionSandboxMode);
        this.workspaceManager = workspaceManager;
        this.stateStore = stateStore;
    }

    /**
     * 初始化状态存储.
     */
    public void init() {
        String storeType = configManager.stateStoreType();
        Path statePath = configManager.resolveStateDir();

        if (this.stateStore == null) {
            if (!"json_file".equals(storeType) && !"json".equals(storeType)) {
                log.warn("State store '{}' is not injected; falling back to JSON file", storeType);
            }
            this.stateStore = new JsonFileAgentStateStore(statePath);
        }
        log.info("State store initialized: type={}, implementation={}",
                storeType, this.stateStore.getClass().getSimpleName());
    }

    /**
     * 获取或创建 Agent 单例.
     */
    public HarnessAgent getOrCreate(String agentId) {
        return agents.computeIfAbsent(agentId, id -> {
            AgentConfig config = configManager.getConfig().getAgents().get(id);
            if (config == null) {
                throw new IllegalArgumentException("No agent config found for: " + id);
            }
            if (!config.isEnabled()) {
                throw new IllegalStateException("Agent is disabled: " + id);
            }
            if ((config.getActiveModel() == null || config.getActiveModel().isBlank()) && providerManager != null) {
                Map<String, String> active = providerManager.getActiveModel();
                String providerId = active.get("provider_id");
                String model = active.get("model");
                if (providerId != null && !providerId.isBlank() && model != null && !model.isBlank()) {
                    config.setActiveModel(providerId + ":" + model);
                }
            }
            Path workspaceDir = configManager.resolveWorkspaceDir(id);
            workspaceManager.initWorkspace(workspaceDir);
            workspaceManager.writeAgentJson(workspaceDir, id, config);
            AgentStateStore scopedStateStore = new AgentScopedStateStore(stateStore, id);
            return agentFactory.create(id, config, workspaceDir, scopedStateStore);
        });
    }

    /**
     * 重载 Agent 配置 (配置变更时调用).
     */
    public void reload(String agentId) {
        HarnessAgent old = agents.remove(agentId);
        running.remove(agentId);
        if (old != null) {
            log.info("Agent {} removed for reload", agentId);
        }
    }

    /**
     * 重载所有 Agent.
     */
    public void reloadAll() {
        agents.clear();
        running.clear();
        log.info("All agents cleared for reload");
    }

    /**
     * 启动指定 Agent.
     */
    public void start(String agentId) {
        AgentConfig config = configManager.getConfig().getAgents().get(agentId);
        if (config != null && !config.isEnabled()) {
            log.info("Agent {} is disabled, skip start", agentId);
            return;
        }
        getOrCreate(agentId);
        running.add(agentId);
        log.info("Agent {} started", agentId);
    }

    /**
     * 停止指定 Agent.
     */
    public void stop(String agentId) {
        running.remove(agentId);
        HarnessAgent removed = agents.remove(agentId);
        if (removed != null) {
            log.info("Agent {} stopped and removed", agentId);
        }
    }

    /**
     * 检查 Agent 是否在运行.
     */
    public boolean isRunning(String agentId) {
        return running.contains(agentId) && agents.containsKey(agentId);
    }

    /**
     * 列出所有 Agent 及其状态.
     */
    public Map<String, Map<String, Object>> listAgents() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Map<String, AgentConfig> configs = configManager.getConfig().getAgents();
        for (String id : configs.keySet()) {
            AgentConfig config = configs.get(id);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", config.getName() != null ? config.getName() : id);
            info.put("active_model", config.getActiveModel());
            info.put("enabled", config.isEnabled());
            info.put("running", isRunning(id));
            result.put(id, info);
        }
        return result;
    }

    /**
     * 获取 Agent 实例.
     */
    public HarnessAgent getAgent(String agentId) {
        return agents.get(agentId);
    }

    /** Clears the live and persisted AgentScope state for one authenticated session. */
    public void clearSession(String agentId, String userId, String sessionId) {
        HarnessAgent agent = getOrCreate(agentId);
        AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
        state.contextMutable().clear();
        state.setSummary("");
        state.setCurIter(0);
        state.setShutdownInterrupted(false);
        state.getTasksContext().tasksMutable().clear();
        state.getToolContext().setActivatedGroups(List.of());
        for (String key : List.copyOf(state.getToolContext().getSpawnRegistry().keySet())) {
            state.getToolContext().removeSpawnEntry(key);
        }
        state.getPlanModeContext().setPlanActive(false);
        state.getPlanModeContext().setCurrentPlanFile(null);
        agent.getDelegate().saveAgentState(userId, sessionId);
    }

    /** Forces AgentScope's real conversation compactor to summarize the current session now. */
    public Mono<Boolean> compactSession(String agentId, String userId, String sessionId, String instruction) {
        HarnessAgent agent = getOrCreate(agentId);
        AgentState state = agent.getDelegate().getAgentState(userId, sessionId);
        List<Msg> messages = state.getContext();
        if (messages.size() < 2) {
            return Mono.just(false);
        }

        int keepMessages = Math.min(8, Math.max(1, messages.size() / 3));
        String summaryPrompt = CompactionConfig.DEFAULT_SUMMARY_PROMPT;
        if (instruction != null && !instruction.isBlank()) {
            summaryPrompt = "Additional user instruction for this compaction:\n"
                    + instruction.trim() + "\n\n" + summaryPrompt;
        }
        CompactionConfig forced = CompactionConfig.builder()
                .triggerMessages(2)
                .triggerTokens(1)
                .keepMessages(keepMessages)
                .keepTokens(0)
                .summaryPrompt(summaryPrompt)
                .flushBeforeCompact(true)
                .offloadBeforeCompact(true)
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put("agent_id", agentId)
                .put("root_agent_id", agentId)
                .put("root_session_id", sessionId)
                .build();
        MemoryFlushManager flushManager = new MemoryFlushManager(
                agent.workspaceFor(userId, sessionId), agent.getModel());
        ConversationCompactor compactor = new ConversationCompactor(agent.getModel(), flushManager);
        return compactor.compactIfNeeded(context, messages, forced, agentId, sessionId)
                .map(compacted -> {
                    if (compacted.isEmpty()) return false;
                    List<Msg> replacement = compacted.get();
                    state.contextMutable().clear();
                    state.contextMutable().addAll(replacement);
                    state.setSummary(summaryText(replacement));
                    agent.getDelegate().saveAgentState(userId, sessionId);
                    return true;
                });
    }

    private String summaryText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return "";
        return messages.get(0).getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }

    /**
     * 启动所有配置的 Agent (预热).
     */
    public void startAll() {
        for (String agentId : configManager.getConfig().getAgents().keySet()) {
            try {
                AgentConfig config = configManager.getConfig().getAgents().get(agentId);
                if (config != null && !config.isEnabled()) {
                    continue;
                }
                start(agentId);
            } catch (Exception e) {
                log.error("Failed to start agent {}", agentId, e);
            }
        }
    }

    /**
     * 停止所有 Agent.
     */
    public void stopAll() {
        for (String agentId : new ArrayList<>(running)) {
            stop(agentId);
        }
        log.info("All agents stopped");
    }
}
