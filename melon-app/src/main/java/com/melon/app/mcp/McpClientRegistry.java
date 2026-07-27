package com.melon.app.mcp;

import com.melon.app.config.McpExecutionProperties;
import com.melon.app.config.RuntimeExecutors;
import com.melon.core.config.ConfigManager;
import com.melon.core.security.OutboundAddressGuard;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/** Owns one MCP client manager per Agent so connections and credentials never cross scopes. */
@Component
public class McpClientRegistry {

    private final ConfigManager configManager;
    private final McpClientManager defaultManager;
    private final StdioMcpProcessLauncher stdioProcessLauncher;
    private final ScheduledExecutorService lifecycle;
    private final McpStdioSessionPool stdioSessionPool;
    /** Scoped managers share the same Spring-configured egress policy as the default manager. */
    private final OutboundAddressGuard egressGuard;
    private final Map<String, McpClientManager> scopedManagers = new ConcurrentHashMap<>();

    public McpClientRegistry(ConfigManager configManager, McpClientManager defaultManager) {
        this(configManager, defaultManager, new StdioMcpProcessLauncher(),
                (ScheduledExecutorService) null, OutboundAddressGuard.strict(),
                McpStdioSessionPool.standalone());
    }

    @Autowired
    public McpClientRegistry(ConfigManager configManager, McpClientManager defaultManager,
                             StdioMcpProcessLauncher stdioProcessLauncher,
                             RuntimeExecutors executors, McpExecutionProperties properties,
                             McpStdioSessionPool stdioSessionPool) {
        this(configManager, defaultManager, stdioProcessLauncher, executors.mcpLifecycle(),
                McpClientManager.egressGuardFrom(properties), stdioSessionPool);
    }

    private McpClientRegistry(ConfigManager configManager, McpClientManager defaultManager,
                              StdioMcpProcessLauncher stdioProcessLauncher,
                              ScheduledExecutorService lifecycle, OutboundAddressGuard egressGuard,
                              McpStdioSessionPool stdioSessionPool) {
        this.configManager = configManager;
        this.defaultManager = defaultManager;
        this.stdioProcessLauncher = stdioProcessLauncher;
        this.lifecycle = lifecycle;
        this.egressGuard = egressGuard;
        this.stdioSessionPool = stdioSessionPool;
    }

    public McpClientManager forAgent(String agentId) {
        String scope = normalize(agentId);
        if ("default".equals(scope)) return defaultManager;
        if (configManager.getConfig().getAgent(scope) == null) {
            throw new IllegalArgumentException("Agent not found: " + scope);
        }
        return scopedManagers.computeIfAbsent(scope, this::createManager);
    }

    private McpClientManager createManager(String agentId) {
        Path workspaceDir = configManager.resolveWorkspaceDir(agentId);
        Path configFile = workspaceDir
                .resolve(".melon")
                .resolve("mcp-clients.json");
        McpClientManager manager = lifecycle == null
                ? new McpClientManager(configManager, configFile, workspaceDir,
                        stdioProcessLauncher, egressGuard, agentId, stdioSessionPool)
                : new McpClientManager(configManager, configFile, workspaceDir,
                        stdioProcessLauncher, lifecycle, false, egressGuard, agentId,
                        stdioSessionPool);
        manager.init();
        return manager;
    }

    public void reloadAgent(String agentId) {
        String scope = normalize(agentId);
        if ("default".equals(scope)) {
            defaultManager.reload();
            return;
        }
        McpClientManager manager = scopedManagers.remove(scope);
        if (manager != null) manager.shutdown();
    }

    private String normalize(String agentId) {
        String value = agentId == null || agentId.isBlank() ? "default" : agentId.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid Agent id");
        }
        return value;
    }

    @PreDestroy
    public void shutdown() {
        scopedManagers.values().forEach(McpClientManager::shutdown);
        scopedManagers.clear();
    }
}
