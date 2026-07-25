package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.auth.ProjectAgentService;
import com.melon.app.mcp.McpAccessPolicyService;
import com.melon.app.mcp.McpClientManager;
import com.melon.app.mcp.McpClientRegistry;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * MCP REST 控制器. 对应 Python /api/mcp/* 端点.
 * <p>
 * 端点:
 * <ul>
 *   <li>GET /api/mcp/servers - 列出所有 MCP 服务器</li>
 *   <li>POST /api/mcp/servers - 注册 MCP 服务器</li>
 *   <li>DELETE /api/mcp/servers/{id} - 移除 MCP 服务器</li>
 *   <li>GET /api/mcp/servers/{id}/tools - 列出服务器工具</li>
 *   <li>POST /api/mcp/servers/{id}/tools - 调用服务器工具</li>
 *   <li>POST /api/mcp/reload - 热重载所有服务器</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpClientRegistry mcpClientRegistry;
    private final ConfigManager configManager;
    private final MultiAgentManager multiAgentManager;
    private final McpAccessPolicyService accessPolicyService;
    private final EnterpriseAgentAccessService agentAccessService;
    private final ProjectAgentService projectAgentService;

    public McpController(McpClientRegistry mcpClientRegistry, ConfigManager configManager,
                         MultiAgentManager multiAgentManager, McpAccessPolicyService accessPolicyService,
                         EnterpriseAgentAccessService agentAccessService,
                         ProjectAgentService projectAgentService) {
        this.mcpClientRegistry = mcpClientRegistry;
        this.configManager = configManager;
        this.multiAgentManager = multiAgentManager;
        this.accessPolicyService = accessPolicyService;
        this.agentAccessService = agentAccessService;
        this.projectAgentService = projectAgentService;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listClients(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        return blocking(() -> {
            McpClientManager manager = manager(resolvedAgentId);
            return ResponseEntity.ok(manager.listServers().stream()
                    .map(config -> clientInfo(resolvedAgentId, manager, config))
                    .collect(Collectors.toList()));
        });
    }

    @GetMapping({"/{clientKey}", "/client"})
    public Mono<ResponseEntity<?>> getClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> {
            McpClientManager manager = manager(resolvedAgentId);
            var config = manager.getServer(resolvedClientKey);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(clientInfo(resolvedAgentId, manager, config));
        });
    }

    @PostMapping
    public Mono<ResponseEntity<?>> createClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP client created", () -> {
            McpClientManager manager = manager(resolvedAgentId);
            var config = McpClientManager.McpServerConfig.fromMap(normalizeClientBody(body));
            manager.register(config);
            reloadAgent(resolvedAgentId);
            return ResponseEntity.status(201).body(clientInfo(resolvedAgentId, manager, config));
        }));
    }

    @PutMapping({"/{clientKey}", "/client"})
    public Mono<ResponseEntity<?>> updateClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP client updated", () -> {
            McpClientManager manager = manager(resolvedAgentId);
            var existing = manager.getServer(resolvedClientKey);
            if (existing == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> merged = mergeClientBody(existing, body);
            merged.put("id", resolvedClientKey);
            var config = McpClientManager.McpServerConfig.fromMap(merged);
            if (manager.replace(resolvedClientKey, config) == null) {
                return ResponseEntity.notFound().build();
            }
            reloadAgent(resolvedAgentId);
            return ResponseEntity.ok(clientInfo(resolvedAgentId, manager, config));
        }));
    }

    @PatchMapping({"/toggle/{clientKey}", "/client/toggle"})
    public Mono<ResponseEntity<?>> toggleClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP client toggled", () -> {
            McpClientManager manager = manager(resolvedAgentId);
            var config = manager.getServer(resolvedClientKey);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            manager.setEnabled(resolvedClientKey, !config.isEnabled());
            reloadAgent(resolvedAgentId);
            return ResponseEntity.ok(clientInfo(resolvedAgentId, manager, config));
        }));
    }

    @DeleteMapping({"/{clientKey}", "/client"})
    public Mono<ResponseEntity<?>> deleteClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP client deleted", () -> {
            boolean removed = manager(resolvedAgentId).unregister(resolvedClientKey);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            reloadAgent(resolvedAgentId);
            return ResponseEntity.ok(Map.of("message", "deleted"));
        }));
    }

    @GetMapping({"/tools/{clientKey}", "/client/tools"})
    public Mono<ResponseEntity<?>> listClientTools(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> ResponseEntity.ok(toolInfos(manager(resolvedAgentId), resolvedClientKey)));
    }

    @PutMapping({"/tools/{clientKey}", "/client/tools"})
    public Mono<ResponseEntity<?>> updateToolWhitelist(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP tool policy updated", () -> {
            McpClientManager manager = manager(resolvedAgentId);
            var config = manager.getServer(resolvedClientKey);
            if (config == null) return ResponseEntity.notFound().build();
            manager.setToolWhitelist(resolvedClientKey, toStringListOrNull(body != null ? body.get("tools") : null));
            reloadAgent(resolvedAgentId);
            return ResponseEntity.ok(toolInfos(manager, resolvedClientKey));
        }));
    }

    @GetMapping("/access-principals")
    public Mono<ResponseEntity<?>> accessPrincipals(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        return blocking(() -> ResponseEntity.ok(recentAccessPrincipals(resolvedAgentId)));
    }

    @GetMapping({"/policy/{clientKey}", "/client/policy"})
    public Mono<ResponseEntity<?>> getPolicy(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> ResponseEntity.ok(policy(resolvedAgentId, resolvedClientKey)));
    }

    @PutMapping({"/policy/{clientKey}", "/client/policy"})
    public Mono<ResponseEntity<?>> updatePolicy(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP access policy updated",
                () -> ResponseEntity.ok(
                        accessPolicyService.save(scope(resolvedAgentId), resolvedClientKey, body))));
    }

    @PostMapping({
            "/oauth/configure/{clientKey}", "/client/oauth/configure",
            "/oauth/start/{clientKey}", "/client/oauth/start"
    })
    public Mono<ResponseEntity<?>> configureOAuth(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP OAuth configured", () -> {
            try {
                Map<String, Object> status = new LinkedHashMap<>(
                        manager(resolvedAgentId).configureOAuth(resolvedClientKey, body));
                return ResponseEntity.ok(status);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
            }
        }));
    }

    @GetMapping({"/oauth/status/{clientKey}", "/client/oauth/status"})
    public Mono<ResponseEntity<?>> oauthStatus(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> ResponseEntity.ok(
                manager(resolvedAgentId).oauthStatus(resolvedClientKey)));
    }

    @DeleteMapping({"/oauth/{clientKey}", "/client/oauth"})
    public Mono<ResponseEntity<?>> revokeOAuth(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable(required = false) String clientKey,
            @RequestParam(value = "client_key", required = false) String queryClientKey,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        String resolvedClientKey = resolveClientKey(clientKey, queryClientKey);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP OAuth removed", () -> {
            if (!manager(resolvedAgentId).clearOAuth(resolvedClientKey)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("message", "oauth disabled"));
        }));
    }

    /**
     * 列出所有 MCP 服务器.
     */
    @GetMapping("/servers")
    public Mono<ResponseEntity<?>> listServers(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        return blocking(() -> {
            McpClientManager manager = manager(resolvedAgentId);
            List<Map<String, Object>> servers = manager.listServers().stream()
                    .map(config -> {
                        var map = serverMap(config);
                        var state = manager.getConnectionState(config.getId());
                        map.put("connected", state.connected());
                        map.put("status", state.status());
                        map.put("restart_count", state.restartCount());
                        map.put("last_checked_at", state.lastCheckedAt());
                        map.put("next_retry_at", state.nextRetryAt());
                        if (state.error() != null) {
                            map.put("error", state.error());
                        }
                        return map;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("servers", servers));
        });
    }

    /**
     * 注册 MCP 服务器.
     * <p>
     * 请求体示例 (stdio):
     * <pre>{@code
     * {
     *   "name": "filesystem",
     *   "transport": "stdio",
     *   "command": "npx",
     *   "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
     *   "enabled": true
     * }
     * }</pre>
     * <p>
     * 请求体示例 (SSE):
     * <pre>{@code
     * {
     *   "name": "remote-tools",
     *   "transport": "sse",
     *   "url": "http://localhost:3001/sse",
     *   "enabled": true
     * }
     * }</pre>
     */
    @PostMapping("/servers")
    public Mono<ResponseEntity<?>> registerServer(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP server registered", () -> {
            try {
                McpClientManager manager = manager(resolvedAgentId);
                var config = McpClientManager.McpServerConfig.fromMap(body);
                manager.register(config);
                reloadAgent(resolvedAgentId);
                log.info("MCP server registered: agent={}, id={}, name={}",
                        scope(resolvedAgentId), config.getId(), config.getName());
                return ResponseEntity.ok(serverMap(config));
            } catch (IllegalArgumentException e) {
                log.warn("Rejected MCP server configuration: {}", e.getMessage());
                throw e;
            } catch (Exception e) {
                log.error("Failed to register MCP server", e);
                throw new IllegalStateException("Failed to register MCP server", e);
            }
        }));
    }

    /**
     * 移除 MCP 服务器.
     */
    @DeleteMapping("/servers/{id}")
    public Mono<ResponseEntity<?>> removeServer(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable String id,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        return blocking(() -> versionedMutation(resolvedAgentId, principal, "MCP server removed", () -> {
            boolean removed = manager(resolvedAgentId).unregister(id);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            reloadAgent(resolvedAgentId);
            return ResponseEntity.ok(Map.of("status", "removed", "id", id));
        }));
    }

    /**
     * 列出服务器的可用工具.
     */
    @GetMapping("/servers/{id}/tools")
    public Mono<ResponseEntity<?>> listTools(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable String id,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        return blocking(() -> {
            McpClientManager manager = manager(resolvedAgentId);
            var config = manager.getServer(id);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            var tools = manager.listTools(id).stream()
                    .map(McpClientManager.McpTool::toMap)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("server_id", id, "tools", tools));
        });
    }

    /**
     * 调用服务器上的工具.
     * <p>
     * 请求体:
     * <pre>{@code
     * {
     *   "tool": "read_file",
     *   "args": {"path": "/tmp/test.txt"}
     * }
     * }</pre>
     */
    @PostMapping("/servers/{id}/tools")
    public Mono<ResponseEntity<?>> callTool(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        return blocking(() -> {
            String toolName = (String) body.get("tool");
            if (toolName == null || toolName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "tool name is required"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) body.get("args");
            var result = manager(resolvedAgentId).callTool(id, toolName, args);
            if (result.containsKey("error")) {
                return ResponseEntity.status(400).body(result);
            }
            return ResponseEntity.ok(result);
        });
    }

    /**
     * 列出所有已连接服务器的全部工具.
     */
    @GetMapping("/tools")
    public Mono<ResponseEntity<?>> listAllTools(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
        return blocking(() ->
                ResponseEntity.ok(Map.of("tools", manager(resolvedAgentId).listAllTools())));
    }

    /**
     * 热重载所有 MCP 服务器.
     */
    @PostMapping("/reload")
    public Mono<ResponseEntity<?>> reload(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String resolvedAgentId = agentAccessService.resolveConfiguration(principal, agentId);
        return blocking(() -> {
            manager(resolvedAgentId).reload();
            reloadAgent(resolvedAgentId);
            return ResponseEntity.ok(Map.of("status", "reloaded"));
        });
    }

    private Map<String, Object> normalizeClientBody(Map<String, Object> body) {
        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        if (body == null) body = Map.of();
        if (body.get("client") instanceof Map<?, ?> client) {
            client.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            Object key = firstPresent(body, "client_key", "key", "id");
            if (key != null) normalized.put("id", String.valueOf(key));
        }
        Object id = firstPresent(body, "id", "key", "client_key");
        Object name = firstPresent(body, "name", "display_name", "client_key", "key");
        normalized.putIfAbsent("id", id != null ? String.valueOf(id) : null);
        normalized.putIfAbsent("name", name != null ? String.valueOf(name) : null);
        normalized.putIfAbsent("transport", String.valueOf(firstPresent(normalized, "transport", "transport_type") != null ? firstPresent(normalized, "transport", "transport_type") : "stdio"));
        normalized.putIfAbsent("command", firstPresent(body, "command", "cmd"));
        normalized.putIfAbsent("args", body.getOrDefault("args", List.of()));
        normalized.putIfAbsent("env", body.getOrDefault("env", Map.of()));
        normalized.putIfAbsent("headers", body.getOrDefault("headers", Map.of()));
        normalized.putIfAbsent("oauth", firstPresent(body, "oauth"));
        normalized.putIfAbsent("cwd", body.getOrDefault("cwd", ""));
        normalized.putIfAbsent("url", firstPresent(body, "url", "server_url"));
        normalized.putIfAbsent("enabled", body.getOrDefault("enabled", true));
        return normalized;
    }

    private Map<String, Object> mergeClientBody(
            McpClientManager.McpServerConfig existing, Map<String, Object> body) {
        Map<String, Object> merged = new LinkedHashMap<>(existing.toMap());
        Map<String, Object> updates = new LinkedHashMap<>();
        if (body != null && body.get("client") instanceof Map<?, ?> client) {
            client.forEach((key, value) -> updates.put(String.valueOf(key), value));
        } else if (body != null) {
            updates.putAll(body);
        }
        copyAlias(updates, "display_name", "name");
        copyAlias(updates, "transport_type", "transport");
        copyAlias(updates, "server_url", "url");
        for (String runtimeField : List.of(
                "id", "key", "client_key", "tools", "connected", "status", "error", "restart_count",
                "last_checked_at", "next_retry_at", "oauth_status", "access_summary", "available")) {
            updates.remove(runtimeField);
        }
        for (var entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (("env".equals(key) || "headers".equals(key) || "oauth".equals(key))
                    && value instanceof Map<?, ?> updateMap
                    && merged.get(key) instanceof Map<?, ?> existingMap) {
                merged.put(key, preserveMaskedValues(existingMap, updateMap));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private void copyAlias(Map<String, Object> values, String alias, String target) {
        if (!values.containsKey(target) && values.containsKey(alias)) values.put(target, values.get(alias));
        values.remove(alias);
    }

    private Map<String, Object> preserveMaskedValues(Map<?, ?> existing, Map<?, ?> updates) {
        Map<String, Object> result = new LinkedHashMap<>();
        updates.forEach((key, value) -> {
            String name = String.valueOf(key);
            Object previous = existing.get(key);
            result.put(name, "***".equals(value) && previous != null ? previous : value);
        });
        return result;
    }

    private Object firstPresent(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return body.get(key);
            }
        }
        return null;
    }

    private McpClientManager manager(String agentId) {
        return mcpClientRegistry.forAgent(scope(agentId));
    }

    private <T> T versionedMutation(String agentId, EnterprisePrincipal principal,
                                    String summary, Callable<T> mutation) throws Exception {
        return projectAgentService.mutateResource(scope(agentId), principal, summary, mutation);
    }

    private <T> Mono<T> blocking(Callable<T> task) {
        return Mono.fromCallable(task).subscribeOn(Schedulers.boundedElastic());
    }

    private String scope(String agentId) {
        return AgentRequestSupport.agentId(agentId);
    }

    private String resolveClientKey(String pathClientKey, String queryClientKey) {
        String clientKey = queryClientKey != null ? queryClientKey : pathClientKey;
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("MCP client key is required");
        }
        return clientKey;
    }

    private void reloadAgent(String agentId) {
        multiAgentManager.reload(scope(agentId));
    }

    private Map<String, Object> clientInfo(String agentId, McpClientManager manager,
                                           McpClientManager.McpServerConfig config) {
        Map<String, Object> map = serverMap(config);
        var state = manager.getConnectionState(config.getId());
        map.put("key", config.getId());
        map.put("client_key", config.getId());
        map.put("display_name", config.getName());
        map.put("description", config.getDescription() == null ? "" : config.getDescription());
        map.putIfAbsent("headers", Map.of());
        map.putIfAbsent("cwd", "");
        map.put("tools", config.getToolWhitelist());
        map.put("oauth", safeOAuth(config.getOAuth()));
        map.put("oauth_status", config.getOAuth() == null ? null : manager.oauthStatus(config.getId()));
        map.put("access_summary", accessSummary(agentId, config.getId()));
        map.put("connected", state.connected());
        map.put("status", state.status().toLowerCase(java.util.Locale.ROOT));
        map.put("restart_count", state.restartCount());
        map.put("last_checked_at", state.lastCheckedAt());
        map.put("next_retry_at", state.nextRetryAt());
        map.put("available", config.isEnabled());
        if (state.error() != null) {
            map.put("error", state.error());
        }
        return map;
    }

    private Map<String, Object> serverMap(McpClientManager.McpServerConfig config) {
        Map<String, Object> map = new LinkedHashMap<>(config.toMap());
        map.put("env", maskValues(config.getEnv()));
        map.put("headers", maskValues(config.getHeaders()));
        map.put("oauth", safeOAuth(config.getOAuth()));
        return map;
    }

    private Map<String, String> maskValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> masked = new LinkedHashMap<>();
        values.forEach((key, value) -> masked.put(key, value == null || value.isBlank() ? "" : "***"));
        return masked;
    }

    private Map<String, Object> policy(String agentId, String clientKey) {
        return accessPolicyService.load(scope(agentId), clientKey);
    }

    private Map<String, Object> accessSummary(String agentId, String clientKey) {
        Map<String, Object> p = policy(agentId, clientKey);
        int overrides = sizeOf(p.get("client_overrides"))
                + sizeOf(p.get("tool_defaults"))
                + sizeOf(p.get("tool_overrides"));
        return Map.of(
                "default_effect", p.getOrDefault("default_effect", "allow"),
                "overrides_count", overrides);
    }

    private int sizeOf(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private List<Map<String, Object>> toolInfos(McpClientManager manager, String clientKey) {
        var config = manager.getServer(clientKey);
        if (config == null) return List.of();
        List<String> whitelist = config.getToolWhitelist();
        return manager.listTools(clientKey).stream()
                .map(tool -> {
                    Map<String, Object> info = tool.toMap();
                    info.put("enabled", whitelist == null || whitelist.contains(tool.getName()));
                    info.put("client_key", clientKey);
                    return info;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> recentAccessPrincipals(String agentId) {
        List<Map<String, Object>> principals = new java.util.ArrayList<>();
        String currentAgentId = scope(agentId);
        try {
            java.nio.file.Path chats = configManager.resolveWorkspaceDir(currentAgentId).resolve("chats.json");
            Map<String, Object> data = JsonUtils.loadAsMap(chats);
            Object raw = data.get("chats");
            if (!(raw instanceof List<?> list)) return List.of();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> chatRaw)) continue;
                Map<String, Object> chat = new LinkedHashMap<>();
                chatRaw.forEach((k, v) -> chat.put(String.valueOf(k), v));
                String channel = String.valueOf(chat.getOrDefault("channel", "console"));
                String userId = String.valueOf(chat.getOrDefault("user_id", "default"));
                principals.add(Map.of(
                        "source_type", "channel",
                        "source_value", channel,
                        "subject_type", "user",
                        "subject_value", userId,
                        "label", channel + ":" + userId,
                        "chat_id", String.valueOf(chat.getOrDefault("id", "")),
                        "chat_name", String.valueOf(chat.getOrDefault("name", "")),
                        "session_id", String.valueOf(chat.getOrDefault("session_id", "")),
                        "agent_id", currentAgentId
                ));
            }
        } catch (Exception e) {
            log.debug("Failed to read access principals for agent {}", currentAgentId, e);
        }
        return principals.stream().limit(200).toList();
    }

    private Map<String, Object> safeOAuth(McpClientManager.OAuthConfig oauth) {
        if (oauth == null) return null;
        Map<String, Object> map = new LinkedHashMap<>(oauth.toMap());
        if (map.get("client_secret") != null) map.put("client_secret", "***");
        return map;
    }

    private List<String> toStringListOrNull(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

}
