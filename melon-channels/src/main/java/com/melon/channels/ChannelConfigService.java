package com.melon.channels;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChannelConfigService {

    private static final String MASK = "********";
    private static final String INTERNAL_STATE_DIR = "_channel_state_dir";
    private static final Set<String> SERVER_MANAGED_KEYS = Set.of(
            "type", "isBuiltin", "implemented", "available", "supports_qrcode",
            "supports_streaming", "requires_webhook", INTERNAL_STATE_DIR, "bot_token_file",
            "endpoint", "base_url", "api_base", "portal_host", "ws_url",
            "reply_url", "webhook_url",
            "outgoing_webhook", "http_proxy", "proxy", "media_dir", "domain"
    );
    private static final Map<String, Set<String>> AUTHORIZED_CREDENTIAL_KEYS = Map.of(
            "dingtalk", Set.of("client_id", "client_secret"),
            "feishu", Set.of("app_id", "app_secret"),
            "qq", Set.of("app_id", "client_secret", "user_openid"),
            "wechat", Set.of("bot_token", "base_url"),
            "wecom", Set.of("bot_id", "secret")
    );

    private final ConfigManager configManager;
    private final ChannelAdapterRegistry registry;
    private final ChannelAccessControlStore accessControlStore;

    public ChannelConfigService(ConfigManager configManager, ChannelAdapterRegistry registry) {
        this(configManager, registry, null);
    }

    public ChannelConfigService(ConfigManager configManager,
                                ChannelAdapterRegistry registry,
                                ChannelAccessControlStore accessControlStore) {
        this.configManager = configManager;
        this.registry = registry;
        this.accessControlStore = accessControlStore;
    }

    public List<String> types() {
        return ChannelTypes.BUILTIN;
    }

    public Map<String, Map<String, Object>> list(String agentId) {
        AgentConfig agent = agent(agentId);
        Map<String, Map<String, Object>> stored = agent != null && agent.getChannels() != null
                ? agent.getChannels()
                : Map.of();
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String type : ChannelTypes.BUILTIN) {
            result.put(type, publicConfig(type, stored.get(type)));
        }
        for (var entry : stored.entrySet()) {
            if (ChannelTypes.supported(entry.getKey())) {
                result.putIfAbsent(entry.getKey(), publicConfig(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    public Map<String, Object> get(String agentId, String channel) {
        requireSupported(channel);
        return list(agentId).getOrDefault(channel, publicConfig(channel, Map.of()));
    }

    public Map<String, Map<String, Object>> updateAll(String agentId, Map<String, Object> body) {
        if (body == null) return list(agentId);
        for (var entry : body.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> raw) {
                update(agentId, entry.getKey(), copy(raw));
            }
        }
        return list(agentId);
    }

    public Map<String, Object> update(String agentId, String channel, Map<String, Object> body) {
        requireSupported(channel);
        AgentConfig agent = agent(agentId);
        if (agent == null) return publicConfig(channel, body);
        Map<String, Map<String, Object>> channels = new LinkedHashMap<>(
                agent.getChannels() != null ? agent.getChannels() : Map.of());
        Map<String, Object> previous = new LinkedHashMap<>(channels.getOrDefault(channel, defaultConfig(channel)));
        Map<String, Object> trustedServerValues = trustedServerValues(channel, previous);
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        SERVER_MANAGED_KEYS.forEach(merged::remove);
        if (body != null) {
            body.forEach((key, value) -> {
                if (!SERVER_MANAGED_KEYS.contains(key) && !key.startsWith("_")) {
                    merged.put(key, preserveSecret(previous, key, value));
                }
            });
        }
        migrateLegacyAccessFields(agentId, channel, merged, body != null ? body : Map.of());
        merged.putAll(trustedServerValues);
        merged.put("type", channel);
        channels.put(channel, merged);
        agent.setChannels(channels);
        configManager.save();
        return publicConfig(channel, merged);
    }

    public Map<String, Object> updateAuthorizedCredentials(
            String agentId, String channel, Map<String, Object> credentials) {
        requireSupported(channel);
        Set<String> allowed = AUTHORIZED_CREDENTIAL_KEYS.getOrDefault(channel, Set.of());
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("Channel does not support QR-code credentials: " + channel);
        }
        AgentConfig agent = agent(agentId);
        if (agent == null) return publicConfig(channel, credentials);
        Map<String, Map<String, Object>> channels = new LinkedHashMap<>(
                agent.getChannels() != null ? agent.getChannels() : Map.of());
        Map<String, Object> merged = new LinkedHashMap<>(
                channels.getOrDefault(channel, defaultConfig(channel)));
        if (credentials != null) {
            credentials.forEach((key, value) -> {
                if (!allowed.contains(key) || value == null || String.valueOf(value).isBlank()) return;
                merged.put(key, trustedCredential(channel, key, value));
            });
        }
        merged.put("enabled", true);
        merged.put("type", channel);
        channels.put(channel, merged);
        agent.setChannels(channels);
        configManager.save();
        return publicConfig(channel, merged);
    }

    public Map<String, Object> runtimeConfig(String agentId, String channel) {
        requireSupported(channel);
        AgentConfig agent = agent(agentId);
        Map<String, Object> runtime = agent == null || agent.getChannels() == null
                ? defaultConfig(channel)
                : new LinkedHashMap<>(agent.getChannels().getOrDefault(channel, defaultConfig(channel)));
        Map<String, Object> trustedServerValues = trustedServerValues(channel, runtime);
        SERVER_MANAGED_KEYS.forEach(runtime::remove);
        runtime.putAll(trustedServerValues);
        runtime.put(INTERNAL_STATE_DIR, channelStateDir(agentId, channel));
        return runtime;
    }

    public Map<String, Object> typeMeta(String type) {
        requireSupported(type);
        ChannelAdapter adapter = registry.get(type);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", type);
        meta.put("isBuiltin", ChannelTypes.BUILTIN.contains(type));
        meta.put("implemented", adapter.implemented());
        meta.put("supports_qrcode", adapter.supportsQrcode());
        meta.put("supports_streaming", adapter.supportsStreaming());
        meta.put("requires_webhook", adapter.requiresWebhook());
        return meta;
    }

    private Map<String, Object> publicConfig(String channel, Map<String, Object> raw) {
        Map<String, Object> config = new LinkedHashMap<>(defaultConfig(channel));
        if (raw != null) config.putAll(raw);
        SERVER_MANAGED_KEYS.forEach(config::remove);
        config.put("type", channel);
        config.put("isBuiltin", ChannelTypes.BUILTIN.contains(channel));
        ChannelAdapter adapter = registry.get(channel);
        config.put("implemented", adapter.implemented());
        config.put("available", adapter.implemented());
        config.put("supports_qrcode", adapter.supportsQrcode());
        config.put("supports_streaming", adapter.supportsStreaming());
        config.put("requires_webhook", adapter.requiresWebhook());
        for (String key : ChannelTypes.SECRET_KEYS) {
            Object value = config.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                config.put(key, MASK);
            }
        }
        return config;
    }

    private void requireSupported(String channel) {
        if (!ChannelTypes.supported(channel)) {
            throw new IllegalArgumentException("Unsupported channel in domestic deployment: " + channel);
        }
    }

    private Map<String, Object> defaultConfig(String channel) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", "console".equals(channel));
        config.put("bot_prefix", "");
        config.put("filter_tool_messages", false);
        config.put("filter_thinking", false);
        config.put("dm_policy", "open");
        config.put("group_policy", "open");
        config.put("allow_from", List.of());
        config.put("deny_message", "");
        config.put("require_mention", false);
        config.put("no_text_debounce", true);
        config.put("access_control_dm", false);
        config.put("access_control_group", false);
        config.put("dm_disabled", false);
        config.put("group_disabled", false);
        config.put("webhook_url", "");
        config.put("outgoing_webhook", "");
        channelDefaults(channel, config);
        return config;
    }

    private void channelDefaults(String channel, Map<String, Object> config) {
        switch (channel) {
            case "console" -> config.put("media_dir", "");
            case "dingtalk" -> {
                config.put("client_id", ""); config.put("client_secret", ""); config.put("message_type", "markdown");
                config.put("cron_message_type", "markdown"); config.put("card_template_id", ""); config.put("card_template_key", "content");
                config.put("robot_code", ""); config.put("media_dir", ""); config.put("card_auto_layout", false);
                config.put("at_sender_on_reply", false); config.put("streaming_enabled", false); config.put("endpoint", "");
            }
            case "feishu" -> {
                config.put("app_id", ""); config.put("app_secret", ""); config.put("encrypt_key", "");
                config.put("verification_token", ""); config.put("media_dir", ""); config.put("domain", "feishu");
                config.put("streaming_enabled", false); config.put("share_session_in_group", false);
            }
            case "qq" -> {
                config.put("app_id", ""); config.put("client_secret", ""); config.put("ack_message", "");
                config.put("user_openid", ""); config.put("api_base", "https://api.sgroup.qq.com");
                config.put("markdown_enabled", true); config.put("media_dir", ""); config.put("max_reconnect_attempts", 100);
                config.put("portal_host", "q.qq.com");
            }
            case "wecom" -> {
                config.put("bot_id", ""); config.put("secret", ""); config.put("media_dir", "");
                config.put("welcome_text", ""); config.put("share_session_in_group", true);
                config.put("max_reconnect_attempts", -1); config.put("streaming_enabled", false);
                config.put("ws_url", ""); config.put("api_base", ""); config.put("reply_url", "");
            }
            case "wechat" -> {
                config.put("bot_token", ""); config.put("base_url", "");
                config.put("media_dir", ""); config.put("message_merge_enabled", false); config.put("message_merge_delay_ms", 0);
            }
            default -> { }
        }
    }

    private Object preserveSecret(Map<String, Object> previous, String key, Object value) {
        if (ChannelTypes.SECRET_KEYS.contains(key) && MASK.equals(value)) {
            return previous.getOrDefault(key, "");
        }
        return value;
    }

    private Map<String, Object> trustedServerValues(String channel, Map<String, Object> config) {
        if (!"wechat".equals(channel) || config == null) return Map.of();
        Object baseUrl = config.get("base_url");
        if (baseUrl == null || String.valueOf(baseUrl).isBlank()) return Map.of();
        try {
            return Map.of("base_url", trustedCredential(channel, "base_url", baseUrl));
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }

    private Object trustedCredential(String channel, String key, Object value) {
        if (!"wechat".equals(channel) || !"base_url".equals(key)) return value;
        String text = String.valueOf(value).trim();
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid WeChat base URL", e);
        }
        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !("weixin.qq.com".equals(host) || host.endsWith(".weixin.qq.com"))) {
            throw new IllegalArgumentException("Untrusted WeChat base URL");
        }
        return text.replaceAll("/+$", "");
    }

    private void migrateLegacyAccessFields(String agentId, String channel,
                                           Map<String, Object> merged,
                                           Map<String, Object> incoming) {
        if (incoming.containsKey("dm_policy")) {
            String policy = String.valueOf(incoming.get("dm_policy"));
            if ("allowlist".equals(policy)) {
                merged.put("access_control_dm", true);
            } else if ("disabled".equals(policy)) {
                merged.put("dm_disabled", true);
            }
            merged.remove("dm_policy");
        }
        if (incoming.containsKey("group_policy")) {
            String policy = String.valueOf(incoming.get("group_policy"));
            if ("allowlist".equals(policy)) {
                merged.put("access_control_group", true);
            } else if ("disabled".equals(policy)) {
                merged.put("group_disabled", true);
            }
            merged.remove("group_policy");
        }
        Object allowFrom = incoming.get("allow_from");
        if (accessControlStore != null && allowFrom instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> entries = list.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .map(userId -> Map.<String, Object>of("channel", channel, "user_id", userId))
                    .toList();
            if (!entries.isEmpty()) {
                accessControlStore.addUsers(agentId, "whitelist", entries);
            }
            merged.remove("allow_from");
        }
    }

    private AgentConfig agent(String agentId) {
        String id = agentId == null || agentId.isBlank() ? "default" : agentId;
        return configManager.getConfig().getAgents().get(id);
    }

    private String channelStateDir(String agentId, String channel) {
        // Resolve the workspace first to validate that the agent exists and its id/path is managed.
        configManager.resolveWorkspaceDir(agentId);
        return configManager.resolveHomeDir()
                .resolve("runtime")
                .resolve("channels")
                .resolve(agentId)
                .resolve(channel)
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private Map<String, Object> copy(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
