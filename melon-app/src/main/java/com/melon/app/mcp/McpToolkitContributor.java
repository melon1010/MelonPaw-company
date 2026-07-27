package com.melon.app.mcp;

import com.melon.core.agent.ToolkitContributor;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers configured MCP stdio tools into the AgentScope toolkit.
 */
@Component
public class McpToolkitContributor implements ToolkitContributor {

    private static final Logger log = LoggerFactory.getLogger(McpToolkitContributor.class);

    private final McpClientRegistry mcpClientRegistry;
    private final McpAccessPolicyService accessPolicyService;
    private final ObjectProvider<MultiAgentManager> multiAgentManager;

    public McpToolkitContributor(McpClientRegistry mcpClientRegistry,
                                 McpAccessPolicyService accessPolicyService,
                                 ObjectProvider<MultiAgentManager> multiAgentManager) {
        this.mcpClientRegistry = mcpClientRegistry;
        this.accessPolicyService = accessPolicyService;
        this.multiAgentManager = multiAgentManager;
    }

    @Override
    public void contribute(String agentId, AgentConfig agentConfig, Path workspaceDir, Toolkit toolkit) {
        McpClientManager mcpClientManager = mcpClientRegistry.forAgent(agentId);
        for (McpClientManager.McpServerConfig server : mcpClientManager.listServers()) {
            if (!server.isEnabled()) continue;
            if (server.getTools().isEmpty()) {
                // Legacy or newly-created configurations have no cached schema yet. Discover once,
                // persist it, and rebuild the Agent. Configurations with cached schemas remain
                // completely lazy and do not start a process while the toolkit is being built.
                mcpClientManager.connectAsync(server.getId()).thenAccept(connected -> {
                    MultiAgentManager manager = multiAgentManager.getIfAvailable();
                    if (connected && manager != null && manager.getAgent(agentId) != null) {
                        manager.reload(agentId);
                    } else if (connected) {
                        registerTools(agentId, server, mcpClientManager, toolkit);
                    }
                });
                continue;
            }
            registerTools(agentId, server, mcpClientManager, toolkit);
        }
    }

    private void registerTools(String agentId, McpClientManager.McpServerConfig server,
                               McpClientManager mcpClientManager, Toolkit toolkit) {
        synchronized (toolkit) {
            String groupName = groupName(server.getId());
            if (toolkit.getToolGroup(groupName) == null) {
                toolkit.createToolGroup(groupName, groupDescription(server), false);
            }
            for (McpClientManager.McpTool tool : mcpClientManager.listTools(server.getId())) {
                if (server.getToolWhitelist() != null && !server.getToolWhitelist().contains(tool.getName())) {
                    continue;
                }
                String toolName = safeToolName(tool.getName());
                if (toolName.isBlank()) continue;
                if (toolkit.getToolNames().contains(toolName)) {
                    toolName = safeToolName("mcp_" + server.getId() + "_" + tool.getName());
                }
                toolkit.registration()
                        .agentTool(new McpBridgeTool(agentId, server.getId(), server.getName(), tool,
                                toolName, mcpClientManager, accessPolicyService))
                        .group(groupName)
                        .apply();
                log.info("Registered MCP tool into agent toolkit: server={}, tool={}", server.getId(), toolName);
            }
        }
    }

    private String groupName(String serverId) {
        String id = serverId == null ? "server" : serverId;
        return safeToolName("mcp_" + id) + "_" + Integer.toUnsignedString(id.hashCode(), 36);
    }

    private String groupDescription(McpClientManager.McpServerConfig server) {
        StringBuilder description = new StringBuilder("Tools provided by MCP server '")
                .append(server.getName() == null || server.getName().isBlank()
                        ? server.getId() : server.getName())
                .append("'");
        if (server.getDescription() != null && !server.getDescription().isBlank()) {
            description.append(": ").append(server.getDescription().strip());
        } else {
            List<String> capabilities = server.getTools().stream()
                    .map(McpClientManager.McpTool::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .limit(8)
                    .toList();
            if (!capabilities.isEmpty()) {
                description.append(". Capabilities: ").append(String.join(", ", capabilities));
            }
        }
        return description.toString();
    }

    private String safeToolName(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public static final class McpBridgeTool extends ToolBase {
        private final String agentId;
        private final String serverId;
        private final String actualToolName;
        private final McpClientManager mcpClientManager;
        private final McpAccessPolicyService accessPolicyService;

        McpBridgeTool(String agentId, String serverId, String serverName,
                      McpClientManager.McpTool tool, String registeredName,
                      McpClientManager mcpClientManager, McpAccessPolicyService accessPolicyService) {
            super(ToolBase.builder()
                    .name(registeredName)
                    .description(description(serverName, tool))
                    .inputSchema(schema(tool))
                    .readOnly(tool.isReadOnly())
                    // AgentScope may run tools from different MCP servers concurrently. The
                    // manager still serializes requests within each individual stdio/HTTP session.
                    .concurrencySafe(true)
                    .mcp(serverName != null && !serverName.isBlank() ? serverName : serverId));
            this.agentId = agentId;
            this.serverId = serverId;
            this.actualToolName = tool.getName();
            this.mcpClientManager = mcpClientManager;
            this.accessPolicyService = accessPolicyService;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("MCP call requires application risk classification")
                    .decisionReason("safety: classify MCP side effects before execution")
                    .build());
        }

        public String accessEffect(io.agentscope.core.agent.RuntimeContext context) {
            return accessPolicyService.effect(agentId, serverId, actualToolName, context);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Map<String, Object> input = new LinkedHashMap<>(param.getInput());
            return Mono.fromCallable(() -> {
                        String effect = accessPolicyService.effect(
                                agentId, serverId, actualToolName, param.getRuntimeContext());
                        boolean approved = param.getToolUseBlock() != null
                                && param.getToolUseBlock().getState() == ToolCallState.ALLOWED;
                        if ("deny".equals(effect) || ("ask".equals(effect) && !approved)) {
                            String detail = "ask".equals(effect)
                                    ? "MCP policy requires explicit approval before execution"
                                    : "MCP policy denied this tool for the current channel and user";
                            return error(detail);
                        }
                        Map<String, Object> result = mcpClientManager.callTool(serverId, actualToolName, input);
                        if (result.containsKey("error")) {
                            return error(String.valueOf(result.get("error")));
                        }
                        if (Boolean.TRUE.equals(result.get("isError"))) {
                            return error(errorDetail(result));
                        }
                        return ToolResultBlock.text(JsonUtils.toJson(result));
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private ToolResultBlock error(String detail) {
            return ToolResultBlock.error(detail).withState(ToolResultState.ERROR);
        }

        private String errorDetail(Map<String, Object> result) {
            Object content = result.get("content");
            if (content instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> map && map.get("text") != null) {
                        String text = String.valueOf(map.get("text"));
                        if (!text.isBlank()) return text;
                    }
                }
            }
            return "MCP tool returned an error";
        }

        private static String description(String serverName, McpClientManager.McpTool tool) {
            String description = tool.getDescription() != null ? tool.getDescription() : "";
            String prefix = serverName == null || serverName.isBlank() ? "MCP tool" : "MCP tool from " + serverName;
            return description.isBlank() ? prefix : prefix + ": " + description;
        }

        private static Map<String, Object> schema(McpClientManager.McpTool tool) {
            Map<String, Object> schema = tool.getInputSchema();
            if (schema == null || schema.isEmpty()) {
                return Map.of("type", "object", "properties", Map.of());
            }
            return schema;
        }
    }
}
