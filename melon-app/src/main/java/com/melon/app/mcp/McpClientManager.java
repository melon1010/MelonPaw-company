package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) 客户端管理器.
 * <p>
 * 管理 MCP 客户端的生命周期: 注册/连接/断开/重载.
 * 支持 stdio 和 SSE 两种传输方式.
 * <p>
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long LIVENESS_INTERVAL_SECONDS = 30;
    private static final int TOOL_RESULT_RETENTION_DAYS = 7;

    /** 已注册的 MCP 服务器配置. */
    private final ConcurrentHashMap<String, McpServerConfig> servers = new ConcurrentHashMap<>();
    /** 服务器连接状态. */
    private final ConcurrentHashMap<String, ConnectionState> connectionStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StdioSession> stdioSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HttpSession> httpSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OAuthToken> oauthTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> connectionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> connectionAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> restartCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final ScheduledExecutorService lifecycle = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "mcp-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final ConfigManager configManager;
    private final Path persistedConfigFile;
    private volatile boolean shuttingDown;

    @Autowired
    public McpClientManager(ConfigManager configManager) {
        this(configManager, null);
    }

    /** Creates an isolated MCP registry backed by the supplied Agent-local file. */
    public McpClientManager(ConfigManager configManager, Path persistedConfigFile) {
        this.configManager = configManager;
        this.persistedConfigFile = persistedConfigFile;
    }

    @PostConstruct
    public void init() {
        if (!initialized.compareAndSet(false, true)) return;
        loadPersisted();
        cleanupSpilledResults();
        for (McpServerConfig config : servers.values()) {
            if (config.isEnabled()) {
                connectAsync(config.getId());
            }
        }
        lifecycle.scheduleWithFixedDelay(this::checkLiveness,
                LIVENESS_INTERVAL_SECONDS, LIVENESS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("McpClientManager initialized");
    }

    @PreDestroy
    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        lifecycle.shutdownNow();
        log.info("Shutting down McpClientManager, disconnecting {} servers", servers.size());
        for (String id : servers.keySet()) {
            disconnect(id);
        }
    }

    // ======================== Server Management ========================

    /**
     * 注册一个 MCP 服务器.
     *
     * @param config 服务器配置
     * @return 注册后的配置 (含生成的 id)
     */
    public McpServerConfig register(McpServerConfig config) {
        if (config.getId() == null || config.getId().isBlank()) {
            config.setId(UUID.randomUUID().toString().substring(0, 8));
        }
        if (config.getName() == null || config.getName().isBlank()) {
            config.setName("mcp-" + config.getId());
        }
        servers.put(config.getId(), config);
        connectionStates.put(config.getId(), state(config.getId(), false, null,
                config.isEnabled() ? "DISCONNECTED" : "DISABLED", null));
        persist();

        if (config.isEnabled()) {
            connectAsync(config.getId());
        }
        log.info("MCP server registered: id={}, name={}, transport={}",
                config.getId(), config.getName(), config.getTransport());
        return config;
    }

    /**
     * 移除一个 MCP 服务器.
     */
    public boolean unregister(String id) {
        McpServerConfig removed = servers.remove(id);
        if (removed == null) {
            return false;
        }
        disconnect(id);
        connectionStates.remove(id);
        connectionLocks.remove(id);
        restartCounts.remove(id);
        oauthTokens.remove(id);
        persist();
        log.info("MCP server unregistered: id={}", id);
        return true;
    }

    /**
     * 列出所有 MCP 服务器.
     */
    public List<McpServerConfig> listServers() {
        return new ArrayList<>(servers.values());
    }

    /**
     * 获取指定服务器配置.
     */
    public McpServerConfig getServer(String id) {
        return servers.get(id);
    }

    public boolean setToolWhitelist(String id, List<String> tools) {
        McpServerConfig config = servers.get(id);
        if (config == null) return false;
        config.setToolWhitelist(tools);
        persist();
        return true;
    }

    public boolean setEnabled(String id, boolean enabled) {
        McpServerConfig config = servers.get(id);
        if (config == null) return false;
        config.setEnabled(enabled);
        if (enabled) {
            connectAsync(id);
        } else {
            disconnect(id);
        }
        persist();
        return true;
    }

    // ======================== Connection Lifecycle ========================

    /**
     * 连接到 MCP 服务器.
     */
    public boolean connect(String id) {
        McpServerConfig config = servers.get(id);
        if (config == null) {
            log.warn("MCP server not found: id={}", id);
            return false;
        }
        synchronized (connectionLocks.computeIfAbsent(id, ignored -> new Object())) {
            ConnectionState current = connectionStates.get(id);
            if (current != null && current.connected() && isSessionHealthy(config)) return true;
            return connectNow(config, false);
        }
    }

    /** Starts a bounded background connection attempt without blocking configuration APIs. */
    public CompletableFuture<Boolean> connectAsync(String id) {
        McpServerConfig config = servers.get(id);
        if (shuttingDown || config == null || !config.isEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        while (true) {
            CompletableFuture<Boolean> current = connectionAttempts.get(id);
            if (current != null && !current.isDone()) return current;
            CompletableFuture<Boolean> created = new CompletableFuture<>();
            boolean installed = current == null
                    ? connectionAttempts.putIfAbsent(id, created) == null
                    : connectionAttempts.replace(id, current, created);
            if (!installed) continue;
            ConnectionState before = connectionStates.get(id);
            boolean reconnecting = before != null
                    && ("ERROR".equals(before.status()) || before.restartCount() > 0);
            connectionStates.put(id, state(id, false, before == null ? null : before.error(),
                    reconnecting ? "RECONNECTING" : "CONNECTING", null));
            created.whenComplete((ignored, error) -> connectionAttempts.remove(id, created));
            lifecycle.execute(() -> attemptConnection(id, 0, created));
            return created;
        }
    }

    /**
     * 断开 MCP 服务器连接.
     */
    public void disconnect(String id) {
        CompletableFuture<Boolean> attempt = connectionAttempts.remove(id);
        if (attempt != null) attempt.cancel(false);
        ConnectionState state = connectionStates.get(id);
        if (state != null && state.connected()) {
            log.info("Disconnecting MCP server: id={}", id);
        }
        closeSessions(id);
        McpServerConfig config = servers.get(id);
        connectionStates.put(id, state(id, false, null,
                config != null && !config.isEnabled() ? "DISABLED" : "DISCONNECTED", null));
    }

    /**
     * 热重载: 断开所有服务器, 重新连接所有已启用的服务器.
     */
    public void reload() {
        log.info("Hot-reloading MCP servers: {} total", servers.size());
        for (String id : servers.keySet()) {
            disconnect(id);
        }
        for (McpServerConfig config : servers.values()) {
            if (config.isEnabled()) {
                connectAsync(config.getId());
            }
        }
        log.info("MCP servers reloaded");
    }

    /**
     * 获取连接状态.
     */
    public ConnectionState getConnectionState(String id) {
        return connectionStates.getOrDefault(id, state(id, false, "not registered", "ERROR", null));
    }

    // ======================== Tool Operations ========================

    /**
     * 列出服务器的可用工具.
     */
    public List<McpTool> listTools(String id) {
        McpServerConfig config = servers.get(id);
        if (config == null) {
            return List.of();
        }
        return config.getTools();
    }

    /**
     * 调用 MCP 服务器上的工具.
     *
     * @param id       服务器 ID
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具执行结果
     */
    public Map<String, Object> callTool(String id, String toolName, Map<String, Object> args) {
        McpServerConfig config = servers.get(id);
        if (config == null) {
            return Map.of("error", "MCP server not found: " + id);
        }
        ConnectionState state = connectionStates.get(id);
        if (state == null || !state.connected()) {
            if (!connect(id)) return Map.of("error", "MCP server not connected: " + id);
        }

        McpTool tool = config.getTools().stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElse(null);
        if (tool == null) {
            return Map.of("error", "Tool not found: " + toolName);
        }
        if (config.getToolWhitelist() != null && !config.getToolWhitelist().contains(toolName)) {
            return Map.of("error", "Tool disabled by MCP whitelist: " + toolName);
        }

        log.info("Calling MCP tool: server={}, tool={}, args={}", id, toolName, args != null ? args.size() : 0);

        if ("stdio".equals(config.getTransport())) {
            StdioSession session = stdioSessions.get(id);
            if (session == null || !session.isAlive()) {
                if (!connect(id)) {
                    return Map.of("error", "MCP server not connected: " + id);
                }
                session = stdioSessions.get(id);
            }
            try {
                return governToolResult(config, toolName,
                        session.callTool(toolName, args == null ? Map.of() : args,
                                config.timeoutSeconds(toolName)));
            } catch (Exception e) {
                log.error("MCP tool call failed: server={}, tool={}", id, toolName, e);
                markUnhealthy(id, e);
                return Map.of("error", firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        }

        HttpSession session = httpSessions.get(id);
        if (session == null) {
            if (!connect(id)) {
                return Map.of("error", "MCP server not connected: " + id);
            }
            session = httpSessions.get(id);
        }
        try {
            return governToolResult(config, toolName,
                    session.callTool(toolName, args == null ? Map.of() : args,
                            config.timeoutSeconds(toolName)));
        } catch (Exception e) {
            log.error("MCP tool call failed: server={}, tool={}", id, toolName, e);
            markUnhealthy(id, e);
            return Map.of("error", firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    /**
     * 获取所有已连接服务器的全部工具.
     */
    public List<Map<String, Object>> listAllTools() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (McpServerConfig config : servers.values()) {
            ConnectionState state = connectionStates.get(config.getId());
            if (state != null && state.connected()) {
                for (McpTool tool : config.getTools()) {
                    if (config.getToolWhitelist() != null && !config.getToolWhitelist().contains(tool.getName())) {
                        continue;
                    }
                    all.add(Map.of(
                            "server_id", config.getId(),
                            "server_name", config.getName(),
                            "tool_name", tool.getName(),
                            "description", tool.getDescription() != null ? tool.getDescription() : ""
                    ));
                }
            }
        }
        return all;
    }

    public Map<String, Object> configureOAuth(String id, Map<String, Object> body) throws IOException {
        McpServerConfig config = servers.get(id);
        if (config == null) {
            throw new IOException("MCP server not found: " + id);
        }
        OAuthConfig oauth = OAuthConfig.fromMap(body == null ? Map.of() : body);
        if (oauth.getTokenUrl() == null || oauth.getTokenUrl().isBlank()) {
            throw new IOException("OAuth token_url is required");
        }
        config.setOAuth(oauth);
        oauthTokens.remove(id);
        authorizationHeader(config);
        persist();
        if (config.isEnabled() && getConnectionState(id).connected()) {
            disconnect(id);
            connectAsync(id);
        }
        return oauthStatus(id);
    }

    public Map<String, Object> oauthStatus(String id) {
        McpServerConfig config = servers.get(id);
        if (config == null || config.getOAuth() == null || !config.getOAuth().isEnabled()) {
            return Map.of("authorized", false, "expires_at", 0, "scope", "", "client_id", "");
        }
        OAuthToken token = oauthTokens.get(id);
        return Map.of(
                "authorized", token != null && !token.isExpiring(config.getOAuth()),
                "expires_at", token == null ? 0 : token.expiresAt().getEpochSecond(),
                "scope", config.getOAuth().getScope() == null ? "" : config.getOAuth().getScope(),
                "client_id", config.getOAuth().getClientId() == null ? "" : config.getOAuth().getClientId()
        );
    }

    public boolean clearOAuth(String id) {
        McpServerConfig config = servers.get(id);
        if (config == null) return false;
        config.setOAuth(null);
        oauthTokens.remove(id);
        persist();
        if (config.isEnabled() && getConnectionState(id).connected()) {
            disconnect(id);
            connectAsync(id);
        }
        return true;
    }

    // ======================== Internal ========================

    private void attemptConnection(String id, int attempt, CompletableFuture<Boolean> result) {
        if (result.isDone() || shuttingDown) return;
        McpServerConfig config = servers.get(id);
        if (config == null || !config.isEnabled()) {
            result.complete(false);
            return;
        }

        boolean connected;
        synchronized (connectionLocks.computeIfAbsent(id, ignored -> new Object())) {
            ConnectionState current = connectionStates.get(id);
            connected = current != null && current.connected() && isSessionHealthy(config)
                    || connectNow(config, attempt > 0);
        }
        if (connected) {
            result.complete(true);
            return;
        }
        if (attempt >= MAX_RECONNECT_ATTEMPTS || result.isCancelled()) {
            result.complete(false);
            return;
        }

        long delay = Math.min(30, 1L << Math.min(attempt + 1, 4));
        ConnectionState current = getConnectionState(id);
        connectionStates.put(id, state(id, false, current.error(), "RECONNECTING",
                Instant.now().plusSeconds(delay).toString()));
        lifecycle.schedule(() -> attemptConnection(id, attempt + 1, result), delay, TimeUnit.SECONDS);
    }

    private boolean connectNow(McpServerConfig config, boolean restart) {
        String id = config.getId();
        if (shuttingDown || !config.isEnabled()) return false;
        if (restart) restartCounts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
        ConnectionState before = connectionStates.get(id);
        boolean reconnecting = restart || restartCounts.containsKey(id);
        connectionStates.put(id, state(id, false, before == null ? null : before.error(),
                reconnecting ? "RECONNECTING" : "CONNECTING", null));
        closeSessions(id);
        try {
            log.info("Connecting to MCP server: id={}, transport={}", id, config.getTransport());
            if ("stdio".equals(config.getTransport())) {
                log.info("  command: {} {}", config.getCommand(), config.getArgs());
                StdioSession session = StdioSession.start(config);
                try {
                    List<McpTool> tools = session.listTools(config.getStartupTimeoutSeconds()).stream()
                            .map(McpTool::fromMap).toList();
                    if (shuttingDown || !config.isEnabled()) {
                        session.close();
                        return false;
                    }
                    stdioSessions.put(id, session);
                    config.setTools(new ArrayList<>(tools));
                } catch (Exception e) {
                    session.close();
                    throw e;
                }
            } else if (isHttpTransport(config.getTransport())) {
                HttpSession session = new HttpSession(config);
                session.initialize(config.getStartupTimeoutSeconds());
                List<McpTool> tools = session.listTools(config.getStartupTimeoutSeconds()).stream()
                        .map(McpTool::fromMap).toList();
                if (shuttingDown || !config.isEnabled()) return false;
                httpSessions.put(id, session);
                config.setTools(new ArrayList<>(tools));
            } else {
                throw new IOException("Unknown MCP transport: " + config.getTransport());
            }
            connectionStates.put(id, state(id, true, null, "CONNECTED", null));
            persist();
            log.info("MCP server connected: id={}, tools={}", id, config.getTools().size());
            return true;
        } catch (Exception e) {
            closeSessions(id);
            String error = firstNonBlank(e.getMessage(), e.getClass().getSimpleName());
            log.error("Failed to connect MCP server: id={}", id, e);
            connectionStates.put(id, state(id, false, error, "ERROR", null));
            return false;
        }
    }

    private void checkLiveness() {
        if (shuttingDown) return;
        for (McpServerConfig config : servers.values()) {
            String id = config.getId();
            ConnectionState state = connectionStates.get(id);
            if (!config.isEnabled() || state == null || !state.connected()) continue;
            try {
                int timeout = Math.min(config.getStartupTimeoutSeconds(), 10);
                StdioSession stdio = stdioSessions.get(id);
                if (stdio != null) {
                    if (!stdio.isAlive()) throw new EOFException("MCP stdio process exited");
                    stdio.ping(timeout);
                } else {
                    HttpSession http = httpSessions.get(id);
                    if (http == null) throw new IOException("MCP HTTP session is missing");
                    http.ping(timeout);
                }
                connectionStates.put(id, state(id, true, null, "CONNECTED", null));
            } catch (Exception e) {
                log.warn("MCP liveness check failed: id={}", id, e);
                markUnhealthy(id, e);
            }
        }
    }

    private void markUnhealthy(String id, Exception error) {
        closeSessions(id);
        restartCounts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
        connectionStates.put(id, state(id, false,
                firstNonBlank(error.getMessage(), error.getClass().getSimpleName()), "ERROR", null));
        McpServerConfig config = servers.get(id);
        if (!shuttingDown && config != null && config.isEnabled()) connectAsync(id);
    }

    private boolean isSessionHealthy(McpServerConfig config) {
        if ("stdio".equals(config.getTransport())) {
            StdioSession session = stdioSessions.get(config.getId());
            return session != null && session.isAlive();
        }
        return httpSessions.containsKey(config.getId());
    }

    private void closeSessions(String id) {
        StdioSession session = stdioSessions.remove(id);
        if (session != null) session.close();
        httpSessions.remove(id);
    }

    private ConnectionState state(String id, boolean connected, String error, String status, String nextRetryAt) {
        return new ConnectionState(connected, error, status,
                restartCounts.getOrDefault(id, new AtomicInteger()).get(),
                Instant.now().toString(), nextRetryAt);
    }

    private Map<String, Object> governToolResult(
            McpServerConfig config, String toolName, Map<String, Object> result) {
        if (result == null || Boolean.TRUE.equals(result.get("isError"))) return result;
        String json = JsonUtils.toJson(result);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes <= config.getMaxInlineResultBytes()) return result;
        try {
            Path directory = toolResultDirectory();
            Files.createDirectories(directory);
            String safeTool = toolName == null ? "tool"
                    : toolName.replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = directory.resolve(Instant.now().toEpochMilli() + "-" + safeTool + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems still inherit the process umask.
            }
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("content", List.of(Map.of(
                    "type", "text",
                    "text", "MCP result exceeded the inline limit and was saved to " + file.toAbsolutePath())));
            reference.put("isError", false);
            reference.put("result_file", file.toAbsolutePath().toString());
            reference.put("result_bytes", bytes);
            reference.put("truncated", true);
            return reference;
        } catch (IOException e) {
            log.warn("Failed to spill large MCP result, returning it inline: server={}, tool={}",
                    config.getId(), toolName, e);
            return result;
        }
    }

    private Path toolResultDirectory() {
        Path file = configFile().toAbsolutePath().normalize();
        Path parent = file.getParent() != null ? file.getParent() : Path.of(".").toAbsolutePath();
        return parent.resolve("tool-results");
    }

    private void cleanupSpilledResults() {
        Path directory = toolResultDirectory();
        if (!Files.isDirectory(directory)) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(TOOL_RESULT_RETENTION_DAYS));
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.debug("Failed to clean MCP tool result: {}", file, e);
                }
            });
        } catch (IOException e) {
            log.debug("Failed to scan MCP tool result directory: {}", directory, e);
        }
    }

    private void loadPersisted() {
        Map<String, Object> payload = JsonUtils.loadAsMap(configFile());
        Object rawServers = payload.get("servers");
        if (rawServers instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    map.forEach((k, v) -> values.put(String.valueOf(k), v));
                    McpServerConfig config = McpServerConfig.fromMap(values);
                    if (config.getId() != null && !config.getId().isBlank()) {
                        servers.put(config.getId(), config);
                        connectionStates.put(config.getId(), state(config.getId(), false, null,
                                config.isEnabled() ? "DISCONNECTED" : "DISABLED", null));
                    }
                }
            }
        }
    }

    private void persist() {
        JsonUtils.save(configFile(), Map.of("servers", servers.values().stream().map(McpServerConfig::toMap).toList()));
    }

    private Path configFile() {
        return persistedConfigFile != null
                ? persistedConfigFile
                : configManager.resolveStateDir().resolve("mcp-clients.json");
    }

    private boolean isHttpTransport(String transport) {
        return "sse".equals(transport) || "streamable_http".equals(transport) || "http".equals(transport);
    }

    private String authorizationHeader(McpServerConfig config) throws IOException {
        OAuthConfig oauth = config.getOAuth();
        if (oauth == null || !oauth.isEnabled()) return null;
        OAuthToken token = oauthTokens.get(config.getId());
        if (token != null && !token.isExpiring(oauth)) {
            return token.tokenType() + " " + token.accessToken();
        }
        synchronized (oauthTokens) {
            token = oauthTokens.get(config.getId());
            if (token != null && !token.isExpiring(oauth)) {
                return token.tokenType() + " " + token.accessToken();
            }
            OAuthToken fresh = fetchOAuthToken(oauth);
            oauthTokens.put(config.getId(), fresh);
            return fresh.tokenType() + " " + fresh.accessToken();
        }
    }

    private OAuthToken fetchOAuthToken(OAuthConfig oauth) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", firstNonBlank(oauth.getGrantType(), "client_credentials"));
        form.putAll(oauth.getExtraTokenParams());
        if (notBlank(oauth.getScope())) form.put("scope", oauth.getScope());
        if (notBlank(oauth.getAudience())) form.put("audience", oauth.getAudience());
        if ("client_credentials".equals(form.get("grant_type"))) {
            if (!notBlank(oauth.getClientId()) || !notBlank(oauth.getClientSecret())) {
                throw new IOException("OAuth client_credentials requires client_id and client_secret");
            }
            form.put("client_id", oauth.getClientId());
            form.put("client_secret", oauth.getClientSecret());
        } else if ("refresh_token".equals(form.get("grant_type"))) {
            if (!notBlank(oauth.getRefreshToken())) {
                throw new IOException("OAuth refresh_token grant requires refresh_token");
            }
            form.put("refresh_token", oauth.getRefreshToken());
            if (notBlank(oauth.getClientId())) form.put("client_id", oauth.getClientId());
            if (notBlank(oauth.getClientSecret())) form.put("client_secret", oauth.getClientSecret());
        } else {
            throw new IOException("Unsupported OAuth grant type: " + form.get("grant_type"));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(oauth.getTokenUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("OAuth token request failed: HTTP " + response.statusCode());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = JsonUtils.getMapper().readValue(response.body(), LinkedHashMap.class);
            String tokenField = firstNonBlank(oauth.getTokenField(), "access_token");
            Object accessToken = payload.get(tokenField);
            if (accessToken == null || String.valueOf(accessToken).isBlank()) {
                throw new IOException("OAuth token response missing '" + tokenField + "'");
            }
            String tokenTypeField = firstNonBlank(oauth.getTokenTypeField(), "token_type");
            String tokenType = String.valueOf(payload.getOrDefault(tokenTypeField, firstNonBlank(oauth.getDefaultTokenType(), "Bearer")));
            String expiresInField = firstNonBlank(oauth.getExpiresInField(), "expires_in");
            Object expiresRaw = payload.getOrDefault(expiresInField, 3600);
            long expiresIn = 3600L;
            if (expiresRaw instanceof Number number) expiresIn = number.longValue();
            else {
                try {
                    expiresIn = Long.parseLong(String.valueOf(expiresRaw));
                } catch (Exception ignored) {
                }
            }
            return new OAuthToken(String.valueOf(accessToken), tokenType, Instant.now().plusSeconds(Math.max(expiresIn, 1L)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OAuth token request interrupted", e);
        }
    }

    private String formEncode(Map<String, String> form) {
        List<String> pairs = new ArrayList<>();
        form.forEach((key, value) -> pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)));
        return String.join("&", pairs);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ======================== Data Models ========================

    /**
     * MCP 服务器配置.
     */
    public static class McpServerConfig {
        private String id;
        private String name;
        private String description;
        private String transport = "stdio";  // stdio / sse
        private String command;              // stdio: 启动命令
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        private String url;                  // sse: 服务器 URL
        private String cwd;
        private Map<String, String> headers = new HashMap<>();
        private OAuthConfig oauth;
        private boolean enabled = true;
        private List<String> toolWhitelist;
        private List<McpTool> tools = new ArrayList<>();
        private int startupTimeoutSeconds = 60;
        private int toolTimeoutSeconds = 120;
        private Map<String, Integer> toolTimeouts = new LinkedHashMap<>();
        private int maxInlineResultBytes = 20 * 1024;

        public static McpServerConfig fromMap(Map<String, Object> body) {
            McpServerConfig config = new McpServerConfig();
            if (body.get("id") != null) config.setId((String) body.get("id"));
            if (body.get("name") != null) config.setName((String) body.get("name"));
            if (body.get("description") != null) config.setDescription(String.valueOf(body.get("description")));
            if (body.get("transport") != null) config.setTransport(String.valueOf(body.get("transport")));
            if (body.get("type") != null) config.setTransport(String.valueOf(body.get("type")));
            if (body.get("command") != null) config.setCommand((String) body.get("command"));
            if (body.get("url") != null) config.setUrl((String) body.get("url"));
            if (body.get("cwd") != null) config.setCwd((String) body.get("cwd"));
            if (body.get("enabled") != null) config.setEnabled((Boolean) body.get("enabled"));
            config.setStartupTimeoutSeconds(intValue(body.get("startup_timeout_seconds"), 60));
            config.setToolTimeoutSeconds(intValue(body.get("tool_timeout_seconds"), 120));
            config.setMaxInlineResultBytes(intValue(body.get("max_inline_result_bytes"), 20 * 1024));
            if (body.get("args") instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> argsList = (List<String>) body.get("args");
                config.setArgs(argsList);
            }
            if (body.get("env") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> envMap = (Map<String, String>) body.get("env");
                config.setEnv(envMap);
            }
            if (body.get("headers") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> headerMap = (Map<String, String>) body.get("headers");
                config.setHeaders(headerMap);
            }
            if (body.get("tool_timeouts") instanceof Map<?, ?> timeoutMap) {
                Map<String, Integer> timeouts = new LinkedHashMap<>();
                timeoutMap.forEach((key, value) -> {
                    String tool = String.valueOf(key).trim();
                    int timeout = intValue(value, 0);
                    if (!tool.isBlank() && timeout > 0) timeouts.put(tool, Math.min(timeout, 3600));
                });
                config.setToolTimeouts(timeouts);
            }
            if (body.get("oauth") instanceof Map<?, ?> oauth) {
                Map<String, Object> values = new LinkedHashMap<>();
                oauth.forEach((k, v) -> values.put(String.valueOf(k), v));
                config.setOAuth(OAuthConfig.fromMap(values));
            }
            if (body.containsKey("tool_whitelist")) {
                config.setToolWhitelist(stringListOrNull(body.get("tool_whitelist")));
            } else if (body.containsKey("toolWhitelist")) {
                config.setToolWhitelist(stringListOrNull(body.get("toolWhitelist")));
            } else if (body.containsKey("tools") && body.get("tools") == null) {
                config.setToolWhitelist(null);
            }
            if (body.get("tools") instanceof List<?> list) {
                if (list.stream().allMatch(item -> item instanceof String)) {
                    config.setToolWhitelist(stringListOrNull(list));
                    return config;
                }
                List<McpTool> tools = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String) {
                        // tools:string[] is the frontend whitelist shape; tool definitions stay in tool_whitelist/tools map below.
                        continue;
                    } else if (item instanceof Map<?, ?> map) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        map.forEach((k, v) -> values.put(String.valueOf(k), v));
                        tools.add(McpTool.fromMap(values));
                    }
                }
                config.setTools(tools);
            }
            return config;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("transport", transport);
            m.put("command", command);
            m.put("args", args);
            m.put("env", env);
            m.put("url", url);
            m.put("cwd", cwd);
            m.put("headers", headers);
            m.put("oauth", oauth == null ? null : oauth.toMap());
            m.put("enabled", enabled);
            m.put("tool_whitelist", toolWhitelist);
            m.put("tools", tools.stream().map(McpTool::toMap).toList());
            m.put("startup_timeout_seconds", startupTimeoutSeconds);
            m.put("tool_timeout_seconds", toolTimeoutSeconds);
            m.put("tool_timeouts", toolTimeouts);
            m.put("max_inline_result_bytes", maxInlineResultBytes);
            return m;
        }

        // getters/setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = "http".equals(transport) ? "streamable_http" : transport; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getCwd() { return cwd; }
        public void setCwd(String cwd) { this.cwd = cwd; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers == null ? new HashMap<>() : headers; }
        public OAuthConfig getOAuth() { return oauth; }
        public void setOAuth(OAuthConfig oauth) { this.oauth = oauth; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getToolWhitelist() { return toolWhitelist; }
        public void setToolWhitelist(List<String> toolWhitelist) { this.toolWhitelist = toolWhitelist; }
        public List<McpTool> getTools() { return tools; }
        public void setTools(List<McpTool> tools) { this.tools = tools; }
        public int getStartupTimeoutSeconds() { return startupTimeoutSeconds; }
        public void setStartupTimeoutSeconds(int seconds) {
            this.startupTimeoutSeconds = Math.max(1, Math.min(seconds, 600));
        }
        public int getToolTimeoutSeconds() { return toolTimeoutSeconds; }
        public void setToolTimeoutSeconds(int seconds) {
            this.toolTimeoutSeconds = Math.max(1, Math.min(seconds, 3600));
        }
        public Map<String, Integer> getToolTimeouts() { return toolTimeouts; }
        public void setToolTimeouts(Map<String, Integer> toolTimeouts) {
            this.toolTimeouts = toolTimeouts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(toolTimeouts);
        }
        public int getMaxInlineResultBytes() { return maxInlineResultBytes; }
        public void setMaxInlineResultBytes(int bytes) {
            this.maxInlineResultBytes = Math.max(16 * 1024, Math.min(bytes, 10 * 1024 * 1024));
        }
        int timeoutSeconds(String toolName) {
            return toolTimeouts.getOrDefault(toolName, toolTimeoutSeconds);
        }

        private static List<String> stringListOrNull(Object value) {
            if (value == null) return null;
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().filter(Objects::nonNull).map(String::valueOf).filter(s -> !s.isBlank()).distinct().toList();
        }

        private static int intValue(Object value, int fallback) {
            if (value instanceof Number number) return number.intValue();
            if (value != null) {
                try {
                    return Integer.parseInt(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                }
            }
            return fallback;
        }
    }

    /**
     * MCP 工具定义.
     */
    public static class McpTool {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
        private Map<String, Object> annotations;

        public McpTool() {}

        public McpTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description != null ? description : "");
            m.put("input_schema", inputSchema != null ? inputSchema : Map.of("type", "object", "properties", Map.of()));
            m.put("annotations", annotations != null ? annotations : Map.of());
            return m;
        }

        public static McpTool fromMap(Map<String, Object> map) {
            McpTool tool = new McpTool();
            tool.setName(String.valueOf(map.getOrDefault("name", "")));
            tool.setDescription(String.valueOf(map.getOrDefault("description", "")));
            Object schema = map.get("input_schema");
            if (schema == null) schema = map.get("inputSchema");
            if (schema instanceof Map<?, ?> raw) {
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                tool.setInputSchema(values);
            }
            Object annotations = map.get("annotations");
            if (annotations instanceof Map<?, ?> raw) {
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                tool.setAnnotations(values);
            }
            return tool;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getInputSchema() { return inputSchema; }
        public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
        public Map<String, Object> getAnnotations() { return annotations; }
        public void setAnnotations(Map<String, Object> annotations) { this.annotations = annotations; }
        public boolean isReadOnly() {
            if (annotations == null) return false;
            Object hint = annotations.get("readOnlyHint");
            if (hint == null) hint = annotations.get("read_only_hint");
            return Boolean.TRUE.equals(hint) || "true".equalsIgnoreCase(String.valueOf(hint));
        }
    }

    /**
     * 连接状态.
     */
    public record ConnectionState(
            boolean connected,
            String error,
            String status,
            int restartCount,
            String lastCheckedAt,
            String nextRetryAt) {
        public ConnectionState(boolean connected, String error) {
            this(connected, error, connected ? "CONNECTED" : "DISCONNECTED", 0,
                    Instant.now().toString(), null);
        }
    }

    public record OAuthToken(String accessToken, String tokenType, Instant expiresAt) {
        boolean isExpiring(OAuthConfig oauth) {
            int skew = oauth == null ? 60 : Math.max(oauth.getRefreshSkewSeconds(), 0);
            return expiresAt.isBefore(Instant.now().plusSeconds(skew));
        }
    }

    public static class OAuthConfig {
        private boolean enabled = true;
        private String tokenUrl;
        private String grantType = "client_credentials";
        private String clientId;
        private String clientSecret;
        private String refreshToken;
        private String scope;
        private String audience;
        private String tokenField = "access_token";
        private String tokenTypeField = "token_type";
        private String expiresInField = "expires_in";
        private String defaultTokenType = "Bearer";
        private int refreshSkewSeconds = 60;
        private Map<String, String> extraTokenParams = new LinkedHashMap<>();

        public static OAuthConfig fromMap(Map<String, Object> body) {
            OAuthConfig config = new OAuthConfig();
            Object enabled = body.get("enabled");
            if (enabled instanceof Boolean b) config.setEnabled(b);
            config.setTokenUrl(stringValue(firstPresent(body, "token_url", "token_endpoint")));
            config.setGrantType(firstNonBlank(stringValue(body.get("grant_type")), "client_credentials"));
            config.setClientId(stringValue(body.get("client_id")));
            config.setClientSecret(stringValue(body.get("client_secret")));
            config.setRefreshToken(stringValue(body.get("refresh_token")));
            config.setScope(stringValue(body.get("scope")));
            config.setAudience(stringValue(body.get("audience")));
            config.setTokenField(firstNonBlank(stringValue(body.get("token_field")), "access_token"));
            config.setTokenTypeField(firstNonBlank(stringValue(body.get("token_type_field")), "token_type"));
            config.setExpiresInField(firstNonBlank(stringValue(body.get("expires_in_field")), "expires_in"));
            config.setDefaultTokenType(firstNonBlank(stringValue(body.get("default_token_type")), "Bearer"));
            Object skew = body.get("refresh_skew_seconds");
            if (skew instanceof Number number) config.setRefreshSkewSeconds(number.intValue());
            Object extra = body.get("extra_token_params");
            if (extra instanceof Map<?, ?> map) {
                Map<String, String> values = new LinkedHashMap<>();
                map.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
                config.setExtraTokenParams(values);
            }
            return config;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enabled", enabled);
            map.put("token_url", tokenUrl);
            map.put("grant_type", grantType);
            map.put("client_id", clientId);
            map.put("client_secret", clientSecret);
            map.put("refresh_token", refreshToken);
            map.put("scope", scope);
            map.put("audience", audience);
            map.put("token_field", tokenField);
            map.put("token_type_field", tokenTypeField);
            map.put("expires_in_field", expiresInField);
            map.put("default_token_type", defaultTokenType);
            map.put("refresh_skew_seconds", refreshSkewSeconds);
            map.put("extra_token_params", extraTokenParams);
            return map;
        }

        private static Object firstPresent(Map<String, Object> body, String... keys) {
            for (String key : keys) {
                if (body.containsKey(key)) return body.get(key);
            }
            return null;
        }

        private static String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static String firstNonBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTokenUrl() { return tokenUrl; }
        public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
        public String getGrantType() { return grantType; }
        public void setGrantType(String grantType) { this.grantType = grantType; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getTokenField() { return tokenField; }
        public void setTokenField(String tokenField) { this.tokenField = tokenField; }
        public String getTokenTypeField() { return tokenTypeField; }
        public void setTokenTypeField(String tokenTypeField) { this.tokenTypeField = tokenTypeField; }
        public String getExpiresInField() { return expiresInField; }
        public void setExpiresInField(String expiresInField) { this.expiresInField = expiresInField; }
        public String getDefaultTokenType() { return defaultTokenType; }
        public void setDefaultTokenType(String defaultTokenType) { this.defaultTokenType = defaultTokenType; }
        public int getRefreshSkewSeconds() { return refreshSkewSeconds; }
        public void setRefreshSkewSeconds(int refreshSkewSeconds) { this.refreshSkewSeconds = refreshSkewSeconds; }
        public Map<String, String> getExtraTokenParams() { return extraTokenParams; }
        public void setExtraTokenParams(Map<String, String> extraTokenParams) { this.extraTokenParams = extraTokenParams == null ? new LinkedHashMap<>() : extraTokenParams; }
    }

    private final class HttpSession {
        private static final AtomicLong IDS = new AtomicLong();
        private final McpServerConfig config;
        private final Object lock = new Object();
        private String sessionId;

        private HttpSession(McpServerConfig config) {
            this.config = config;
        }

        void initialize(int timeoutSeconds) throws IOException {
            request("initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "melonpaw-java", "version", "1.0.0")
            ), timeoutSeconds);
            notify("notifications/initialized", Map.of(), timeoutSeconds);
        }

        List<Map<String, Object>> listTools(int timeoutSeconds) throws IOException {
            Object result = request("tools/list", Map.of(), timeoutSeconds);
            if (result instanceof Map<?, ?> map && map.get("tools") instanceof List<?> list) {
                List<Map<String, Object>> tools = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                        Object inputSchema = values.remove("inputSchema");
                        if (inputSchema != null && !values.containsKey("input_schema")) {
                            values.put("input_schema", inputSchema);
                        }
                        tools.add(values);
                    }
                }
                return tools;
            }
            return List.of();
        }

        Map<String, Object> callTool(
                String name, Map<String, Object> arguments, int timeoutSeconds) throws IOException {
            Object result = request("tools/call", Map.of("name", name, "arguments", arguments), timeoutSeconds);
            if (result instanceof Map<?, ?> raw) {
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                return values;
            }
            return Map.of("result", result);
        }

        void ping(int timeoutSeconds) throws IOException {
            request("ping", Map.of(), timeoutSeconds);
        }

        private Object request(String method, Map<String, Object> params, int timeoutSeconds) throws IOException {
            long id = IDS.incrementAndGet();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("method", method);
            payload.put("params", params);
            Map<String, Object> response = send(payload, true, timeoutSeconds);
            Object responseId = response.get("id");
            if (responseId != null && !String.valueOf(responseId).equals(String.valueOf(id))) {
                throw new IOException("MCP response id mismatch");
            }
            if (response.get("error") != null) {
                throw new IOException(String.valueOf(response.get("error")));
            }
            return response.get("result");
        }

        private void notify(String method, Map<String, Object> params, int timeoutSeconds) throws IOException {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            payload.put("params", params);
            send(payload, false, timeoutSeconds);
        }

        private Map<String, Object> send(
                Map<String, Object> payload, boolean expectBody, int timeoutSeconds) throws IOException {
            synchronized (lock) {
                HttpResponse<String> response = sendOnce(payload, timeoutSeconds);
                if (response.statusCode() == 401 && config.getOAuth() != null) {
                    oauthTokens.remove(config.getId());
                    response = sendOnce(payload, timeoutSeconds);
                }
                response.headers().firstValue("mcp-session-id").ifPresent(value -> sessionId = value);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("MCP HTTP request failed: HTTP " + response.statusCode());
                }
                if (!expectBody || response.body() == null || response.body().isBlank()) {
                    return new LinkedHashMap<>();
                }
                return parseResponse(response.body());
            }
        }

        private HttpResponse<String> sendOnce(Map<String, Object> payload, int timeoutSeconds) throws IOException {
            if (config.getUrl() == null || config.getUrl().isBlank()) {
                throw new IOException("HTTP/SSE MCP url is required");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload)));
            Map<String, String> headers = new LinkedHashMap<>();
            if (config.getHeaders() != null) headers.putAll(config.getHeaders());
            String auth = authorizationHeader(config);
            if (auth != null && !auth.isBlank()) headers.put("Authorization", auth);
            if (sessionId != null && !sessionId.isBlank()) headers.put("Mcp-Session-Id", sessionId);
            headers.forEach(builder::header);
            try {
                return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("MCP HTTP request interrupted", e);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> parseResponse(String body) throws IOException {
            String json = body.stripLeading().startsWith("{") ? body : sseData(body);
            return JsonUtils.getMapper().readValue(json, LinkedHashMap.class);
        }

        private String sseData(String body) throws IOException {
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\R")) {
                if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (data.isEmpty()) {
                throw new IOException("MCP SSE response missing data");
            }
            return data.toString();
        }
    }

    private static final class StdioSession implements AutoCloseable {
        private static final int STDERR_LIMIT = 8192;
        private static final AtomicLong IDS = new AtomicLong();
        private final Process process;
        private final BufferedWriter stdin;
        private final BlockingQueue<ReadResult> responses = new LinkedBlockingQueue<>();
        private final StringBuilder stderr = new StringBuilder();
        private final Object lock = new Object();

        private StdioSession(Process process, String id) {
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            startReader(process.getInputStream(), "mcp-stdout-" + id);
            startStderrReader(process.getErrorStream(), "mcp-stderr-" + id);
        }

        static StdioSession start(McpServerConfig config) throws IOException {
            if (config.getCommand() == null || config.getCommand().isBlank()) {
                throw new IOException("stdio MCP command is required");
            }
            List<String> command = new ArrayList<>();
            command.add(config.getCommand());
            command.addAll(config.getArgs() == null ? List.of() : config.getArgs());
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
            if (config.getCwd() != null && !config.getCwd().isBlank()) {
                pb.directory(new File(config.getCwd()));
            }
            retainProcessLaunchEnvironment(pb.environment());
            pb.environment().put("npm_config_yes", "true");
            if (config.getEnv() != null) pb.environment().putAll(config.getEnv());
            Process process = pb.start();
            StdioSession session = new StdioSession(process, config.getId());
            try {
                session.initialize(config.getStartupTimeoutSeconds());
                return session;
            } catch (IOException | RuntimeException e) {
                session.close();
                throw e;
            }
        }

        private static void retainProcessLaunchEnvironment(Map<String, String> environment) {
            Map<String, String> inherited = new LinkedHashMap<>(environment);
            environment.clear();
            for (String key : List.of(
                    "PATH", "Path", "PATHEXT", "SystemRoot", "ComSpec",
                    "HOME", "USERPROFILE", "TEMP", "TMP", "LANG", "LC_ALL")) {
                String value = inherited.get(key);
                if (value != null && !value.isBlank()) {
                    environment.put(key, value);
                }
            }
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void initialize(int timeoutSeconds) throws IOException {
            request("initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "melonpaw-java", "version", "1.0.0")
            ), timeoutSeconds);
            notify("notifications/initialized", Map.of());
        }

        List<Map<String, Object>> listTools(int timeoutSeconds) throws IOException {
            Object result = request("tools/list", Map.of(), timeoutSeconds);
            if (result instanceof Map<?, ?> map && map.get("tools") instanceof List<?> list) {
                List<Map<String, Object>> tools = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                        Object inputSchema = values.remove("inputSchema");
                        if (inputSchema != null && !values.containsKey("input_schema")) {
                            values.put("input_schema", inputSchema);
                        }
                        tools.add(values);
                    }
                }
                return tools;
            }
            return List.of();
        }

        Map<String, Object> callTool(
                String name, Map<String, Object> arguments, int timeoutSeconds) throws IOException {
            Object result = request("tools/call", Map.of("name", name, "arguments", arguments), timeoutSeconds);
            if (result instanceof Map<?, ?> raw) {
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((k, v) -> values.put(String.valueOf(k), v));
                return values;
            }
            return Map.of("result", result);
        }

        void ping(int timeoutSeconds) throws IOException {
            request("ping", Map.of(), timeoutSeconds);
        }

        private Object request(String method, Map<String, Object> params, int timeoutSeconds) throws IOException {
            long id = IDS.incrementAndGet();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("method", method);
            payload.put("params", params);
            synchronized (lock) {
                try {
                    write(payload);
                } catch (IOException e) {
                    throw stop("Failed to write MCP request: " + method, e);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
                while (true) {
                    ReadResult read;
                    try {
                        long remaining = deadline - System.nanoTime();
                        read = remaining > 0 ? responses.poll(remaining, TimeUnit.NANOSECONDS) : null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw stop("MCP request interrupted: " + method, e);
                    }
                    if (read == null) {
                        throw stop("MCP request timed out after " + timeoutSeconds
                                + " seconds: " + method, null);
                    }
                    if (read.error() != null) {
                        throw stop("MCP server closed stdout", read.error());
                    }
                    Map<String, Object> response = read.message();
                    Object responseId = response.get("id");
                    if (responseId == null || !String.valueOf(responseId).equals(String.valueOf(id))) {
                        continue;
                    }
                    if (response.get("error") != null) {
                        throw new IOException(String.valueOf(response.get("error")));
                    }
                    return response.get("result");
                }
            }
        }

        private void notify(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            payload.put("params", params);
            synchronized (lock) {
                write(payload);
            }
        }

        private void write(Map<String, Object> payload) throws IOException {
            stdin.write(JsonUtils.toJson(payload));
            stdin.newLine();
            stdin.flush();
        }

        private void startReader(InputStream stdout, String threadName) {
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isBlank()) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = JsonUtils.getMapper()
                                .readValue(line, LinkedHashMap.class);
                        responses.offer(new ReadResult(message, null));
                    }
                    responses.offer(new ReadResult(null, new EOFException("MCP server closed stdout")));
                } catch (IOException e) {
                    responses.offer(new ReadResult(null, e));
                }
            }, threadName);
            reader.setDaemon(true);
            reader.start();
        }

        private void startStderrReader(InputStream errorStream, String threadName) {
            Thread reader = new Thread(() -> {
                try (Reader source = new InputStreamReader(errorStream, StandardCharsets.UTF_8)) {
                    char[] buffer = new char[512];
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        synchronized (stderr) {
                            int remaining = STDERR_LIMIT - stderr.length();
                            if (remaining > 0) stderr.append(buffer, 0, Math.min(count, remaining));
                        }
                    }
                } catch (IOException ignored) {
                }
            }, threadName);
            reader.setDaemon(true);
            reader.start();
        }

        private IOException stop(String message, Throwable cause) {
            close();
            synchronized (stderr) {
                String details = stderr.toString().strip();
                if (!details.isEmpty()) message += ": " + details;
            }
            return new IOException(message, cause);
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private record ReadResult(Map<String, Object> message, IOException error) {
        }
    }
}
