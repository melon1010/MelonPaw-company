package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.agent.RuntimeContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime authorization for Agent-scoped MCP tools. */
@Component
public class McpAccessPolicyService {

    private final ConfigManager configManager;

    public McpAccessPolicyService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Map<String, Object> load(String serverId) {
        return load("default", serverId);
    }

    public Map<String, Object> load(String agentId, String serverId) {
        Map<String, Object> result = defaultPolicy();
        result.putAll(JsonUtils.loadAsMap(policyFile(agentId, serverId)));
        result.put("default_effect", effect(result.get("default_effect")));
        return result;
    }

    public Map<String, Object> save(String serverId, Map<String, Object> policy) {
        return save("default", serverId, policy);
    }

    public Map<String, Object> save(String agentId, String serverId, Map<String, Object> policy) {
        Map<String, Object> result = defaultPolicy();
        if (policy != null) result.putAll(policy);
        result.put("default_effect", effect(result.get("default_effect")));
        JsonUtils.save(policyFile(agentId, serverId), result);
        return result;
    }

    public String effect(String serverId, String toolName, RuntimeContext context) {
        return effect("default", serverId, toolName, context);
    }

    public String effect(String agentId, String serverId, String toolName, RuntimeContext context) {
        Map<String, Object> policy = load(agentId, serverId);
        String channel = contextValue(context, "channel", "console");
        String userId = context != null && context.getUserId() != null
                ? context.getUserId() : "";

        String decision = bestRuleEffect(policy.get("tool_overrides"), toolName, channel, userId, true);
        if (decision != null) return decision;
        decision = bestRuleEffect(policy.get("client_overrides"), toolName, channel, userId, false);
        if (decision != null) return decision;
        decision = toolDefaultEffect(policy.get("tool_defaults"), toolName);
        return decision != null ? decision : effect(policy.get("default_effect"));
    }

    public Map<String, Object> defaultPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("default_effect", "allow");
        policy.put("client_overrides", List.of());
        policy.put("tool_defaults", List.of());
        policy.put("tool_overrides", List.of());
        policy.put("unmanaged_rules_count", 0);
        return policy;
    }

    private String bestRuleEffect(Object raw, String toolName, String channel,
                                  String userId, boolean toolScoped) {
        if (!(raw instanceof List<?> rules)) return null;
        String selected = null;
        int selectedScore = -1;
        for (Object item : rules) {
            if (!(item instanceof Map<?, ?> rule)) continue;
            String configuredTool = text(rule.get("tool_name"), "*");
            if (toolScoped && !("*".equals(configuredTool) || configuredTool.equals(toolName))) continue;
            String sourceType = text(rule.get("source_type"), "channel");
            String sourceValue = text(rule.get("source_value"), "*");
            if (!"channel".equals(sourceType)
                    || !(sourceValue.isBlank() || "*".equals(sourceValue) || sourceValue.equals(channel))) {
                continue;
            }
            String subjectType = text(rule.get("subject_type"), "all");
            String subjectValue = text(rule.get("subject_value"), "");
            if ("user".equals(subjectType) && (userId.isBlank() || !userId.equals(subjectValue))) continue;
            int score = 0;
            if (toolScoped && configuredTool.equals(toolName)) score += 4;
            if (!sourceValue.isBlank() && !"*".equals(sourceValue)) score += 2;
            if ("user".equals(subjectType)) score += 1;
            if (score > selectedScore) {
                selected = effect(rule.get("effect"));
                selectedScore = score;
            }
        }
        return selected;
    }

    private String toolDefaultEffect(Object raw, String toolName) {
        if (!(raw instanceof List<?> defaults)) return null;
        String wildcard = null;
        for (Object item : defaults) {
            if (!(item instanceof Map<?, ?> value)) continue;
            String configuredTool = text(value.get("tool_name"), "*");
            if (toolName.equals(configuredTool)) return effect(value.get("effect"));
            if ("*".equals(configuredTool)) wildcard = effect(value.get("effect"));
        }
        return wildcard;
    }

    private Path policyFile(String agentId, String serverId) {
        String safeId = serverId == null ? "" : serverId.trim();
        if (!safeId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid MCP server id");
        }
        String scope = agentId == null || agentId.isBlank() ? "default" : agentId.trim();
        if (!scope.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid Agent id");
        }
        Path root = "default".equals(scope)
                ? configManager.resolveStateDir()
                : configManager.resolveWorkspaceDir(scope).resolve(".melon");
        return root.resolve("mcp-policies").resolve(safeId + ".json");
    }

    private String contextValue(RuntimeContext context, String key, String fallback) {
        Object value = context != null ? context.get(key) : null;
        return text(value, fallback);
    }

    private String effect(Object value) {
        String normalized = text(value, "deny").toLowerCase();
        return switch (normalized) {
            case "allow", "ask", "deny" -> normalized;
            default -> "deny";
        };
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
