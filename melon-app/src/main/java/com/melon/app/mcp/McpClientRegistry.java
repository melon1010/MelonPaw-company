package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one MCP client manager per Agent so connections and credentials never cross scopes. */
@Component
public class McpClientRegistry {

    private final ConfigManager configManager;
    private final McpClientManager defaultManager;
    private final Map<String, McpClientManager> scopedManagers = new ConcurrentHashMap<>();

    public McpClientRegistry(ConfigManager configManager, McpClientManager defaultManager) {
        this.configManager = configManager;
        this.defaultManager = defaultManager;
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
        Path configFile = configManager.resolveWorkspaceDir(agentId)
                .resolve(".melon")
                .resolve("mcp-clients.json");
        McpClientManager manager = new McpClientManager(configManager, configFile);
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
