package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.provider.ProviderManager;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;
import com.melon.app.cron.CronExecutor;
import com.melon.app.service.AgentStatsService;
import com.melon.app.service.AuditLogService;
import com.melon.app.service.BuiltinSkillService;
import com.melon.app.service.SecuritySettingsService;
import com.melon.app.service.SkillService;
import com.melon.app.service.ApprovalService;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.auth.EnterpriseIdentityStore;
import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.util.LanguageNormalizer;
import com.melon.app.config.RuntimeExecutors;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.HeartbeatConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.melon.core.util.ValueUtils.stringValue;
import static com.melon.app.auth.EnterprisePermissions.AGENTS_MANAGE;
import static com.melon.app.auth.EnterprisePermissions.APPROVALS_DECIDE;

/**
 * Console API endpoints that bridge frontend settings to the Java runtime.
 */
@RestController
@RequestMapping("/api")
public class FrontendCompatController {

    private static final String LANGUAGE_PREFERENCE = "ui.language";

    private final ConfigManager configManager;
    private final ProviderManager providerManager;
    private final ApprovalService approvalService;
    private final MultiAgentManager multiAgentManager;
    private final CronExecutor cronExecutor;
    private final AgentStatsService agentStatsService;
    private final SecuritySettingsService securitySettingsService;
    private final AuditLogService auditLogService;
    private final BuiltinSkillService builtinSkillService;
    private final SkillService skillService;
    private final EnterpriseAgentAccessService agentAccessService;
    private final EnterpriseIdentityStore identityStore;
    private final RuntimeExecutors executors;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Map<String, Object> localModelDownload = new ConcurrentHashMap<>();
    private final AtomicReference<CompletableFuture<?>> localModelDownloadFuture = new AtomicReference<>();
    private final AtomicLong localModelDownloadGeneration = new AtomicLong();

    public FrontendCompatController(ConfigManager configManager, ProviderManager providerManager,
                                    ApprovalService approvalService,
                                    MultiAgentManager multiAgentManager,
                                    CronExecutor cronExecutor,
                                    AgentStatsService agentStatsService,
                                    SecuritySettingsService securitySettingsService,
                                    AuditLogService auditLogService,
                                    BuiltinSkillService builtinSkillService,
                                    SkillService skillService,
                                    EnterpriseAgentAccessService agentAccessService,
                                    EnterpriseIdentityStore identityStore) {
        this(configManager, providerManager, approvalService, multiAgentManager, cronExecutor,
                agentStatsService, securitySettingsService, auditLogService, builtinSkillService,
                skillService, agentAccessService, identityStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FrontendCompatController(ConfigManager configManager, ProviderManager providerManager,
                                    ApprovalService approvalService,
                                    MultiAgentManager multiAgentManager,
                                    CronExecutor cronExecutor,
                                    AgentStatsService agentStatsService,
                                    SecuritySettingsService securitySettingsService,
                                    AuditLogService auditLogService,
                                    BuiltinSkillService builtinSkillService,
                                    SkillService skillService,
                                    EnterpriseAgentAccessService agentAccessService,
                                    EnterpriseIdentityStore identityStore,
                                    RuntimeExecutors executors) {
        this.configManager = configManager;
        this.providerManager = providerManager;
        this.approvalService = approvalService;
        this.multiAgentManager = multiAgentManager;
        this.cronExecutor = cronExecutor;
        this.agentStatsService = agentStatsService;
        this.securitySettingsService = securitySettingsService;
        this.auditLogService = auditLogService;
        this.builtinSkillService = builtinSkillService;
        this.skillService = skillService;
        this.agentAccessService = agentAccessService;
        this.identityStore = identityStore;
        this.executors = executors;
    }

    @GetMapping("/version")
    public Mono<ResponseEntity<?>> version() {
        return Mono.just(ResponseEntity.ok(Map.of("version", "1.0.0-SNAPSHOT")));
    }

    @GetMapping("/settings/language")
    public Mono<ResponseEntity<?>> getLanguage(
            @org.springframework.security.core.annotation.AuthenticationPrincipal EnterprisePrincipal principal) {
        Optional<String> preference = principal == null ? Optional.empty()
                : identityStore.getUserPreference(principal.id(), LANGUAGE_PREFERENCE);
        return Mono.just(ResponseEntity.ok(Map.of(
                "language", preference.map(LanguageNormalizer::normalizeOrDefault)
                        .orElse(LanguageNormalizer.DEFAULT_LANGUAGE),
                "configured", preference.isPresent()
        )));
    }

    @PutMapping("/settings/language")
    public Mono<ResponseEntity<?>> setLanguage(
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal EnterprisePrincipal principal) {
        if (principal == null) {
            return Mono.error(new SecurityException("Authentication is required"));
        }
        String requested = stringValue(body != null ? body.get("language") : null, "");
        String language = LanguageNormalizer.normalize(requested)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported language: " + requested));
        identityStore.setUserPreference(principal.id(), LANGUAGE_PREFERENCE, language);
        return Mono.just(ResponseEntity.ok(Map.of("language", language)));
    }

    @GetMapping("/settings/upload-limit")
    public Mono<ResponseEntity<?>> uploadLimit() {
        return Mono.just(ResponseEntity.ok(Map.of("upload_max_size_mb", 100)));
    }

    @GetMapping("/config/user-timezone")
    public Mono<ResponseEntity<?>> getTimezone(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        String timezone = config != null && config.getTimezone() != null && !config.getTimezone().isBlank()
                ? config.getTimezone()
                : TimeZone.getDefault().getID();
        return Mono.just(ResponseEntity.ok(Map.of("timezone", timezone)));
    }

    @PutMapping("/config/user-timezone")
    public Mono<ResponseEntity<?>> setTimezone(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @RequestBody Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        String timezone = stringValue(body != null ? body.get("timezone") : null, TimeZone.getDefault().getID());
        if (config != null) {
            config.setTimezone(timezone);
            configManager.save();
            multiAgentManager.reload(id);
        }
        return Mono.just(ResponseEntity.ok(Map.of("timezone", timezone)));
    }

    @GetMapping("/config/heartbeat")
    public Mono<ResponseEntity<?>> getHeartbeat(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        HeartbeatConfig heartbeat = config != null && config.getHeartbeat() != null ? config.getHeartbeat() : new HeartbeatConfig();
        return Mono.just(ResponseEntity.ok(heartbeatPayload(heartbeat)));
    }

    @PutMapping("/config/heartbeat")
    public Mono<ResponseEntity<?>> setHeartbeat(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        if (config == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        HeartbeatConfig heartbeat = heartbeatFromBody(body);
        config.setHeartbeat(heartbeat);
        configManager.save();
        multiAgentManager.reload(id);
        return Mono.just(ResponseEntity.ok(heartbeatPayload(heartbeat)));
    }

    @PostMapping("/config/heartbeat/run")
    public Mono<ResponseEntity<?>> runHeartbeat(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal EnterprisePrincipal principal) {
        String id = AgentRequestSupport.agentId(agentId);
        return Mono.fromCallable(() -> {
            AgentConfig config = configManager.getConfig().getAgent(id);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            String message = heartbeatInstruction(id);
            String taskId = "heartbeat-manual-" + System.currentTimeMillis();
            String ownerUserId = principal != null && principal.id() != null ? principal.id() : "system";
            cronExecutor.injectMessage(id, message, taskId,
                    ownerUserId, taskId, "console",
                    Map.of("source", "heartbeat", "channel", "console", "execution_user_id", ownerUserId),
                    reply -> {}, err -> {});
            return ResponseEntity.ok(Map.of("started", true));
        }).subscribeOn(webBlockingScheduler());
    }

    @SuppressWarnings("unchecked")
    private HeartbeatConfig heartbeatFromBody(Map<String, Object> body) {
        HeartbeatConfig heartbeat = new HeartbeatConfig();
        if (body == null) return heartbeat;
        heartbeat.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        heartbeat.setEvery(stringValue(body.get("every"), "6h"));
        heartbeat.setTarget(stringValue(body.get("target"), "main"));
        heartbeat.setTimeoutSeconds((int) numberValue(body.get("timeoutSeconds"), 300));
        Object activeHours = body.get("activeHours");
        if (activeHours instanceof Map<?, ?> raw) {
            Map<String, String> value = new LinkedHashMap<>();
            raw.forEach((key, val) -> value.put(String.valueOf(key), String.valueOf(val)));
            heartbeat.setActiveHours(value);
        }
        return heartbeat;
    }

    private Map<String, Object> heartbeatPayload(HeartbeatConfig heartbeat) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", heartbeat.isEnabled());
        result.put("every", heartbeat.getEvery() != null && !heartbeat.getEvery().isBlank() ? heartbeat.getEvery() : "6h");
        result.put("target", heartbeat.getTarget() != null && !heartbeat.getTarget().isBlank() ? heartbeat.getTarget() : "main");
        result.put("timeoutSeconds", heartbeat.getTimeoutSeconds() > 0 ? heartbeat.getTimeoutSeconds() : 300);
        result.put("activeHours", heartbeat.getActiveHours());
        return result;
    }

    private String heartbeatInstruction(String agentId) {
        Path heartbeatFile = SafePathUtil.resolveSafe(
                configManager.resolveWorkspaceDir(agentId), "HEARTBEAT.md");
        if (Files.isRegularFile(heartbeatFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                String text = Files.readString(heartbeatFile).trim();
                if (!text.isBlank()) return text;
            } catch (Exception ignored) {
                // Use default instruction below.
            }
        }
        return "Heartbeat check: please check pending tasks and continue if needed.";
    }

    private double numberValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @GetMapping("/config/security/tool-guard")
    public Mono<ResponseEntity<?>> toolGuard(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        String level = config != null && config.getApproval() != null ? config.getApproval().getLevel() : "AUTO";
        Map<String, Object> result = new LinkedHashMap<>(storedToolGuard(config));
        result.put("enabled", !"OFF".equalsIgnoreCase(level));
        result.put("approval_level", level);
        result.putIfAbsent("guarded_tools", null);
        result.putIfAbsent("denied_tools", List.of());
        result.putIfAbsent("custom_rules", List.of());
        result.putIfAbsent("disabled_rules", List.of());
        result.putIfAbsent("auto_denied_rules", List.of());
        result.putIfAbsent("shell_evasion_checks", Map.of());
        return Mono.just(ResponseEntity.ok(result));
    }

    @PutMapping("/config/security/tool-guard")
    public Mono<ResponseEntity<?>> updateToolGuard(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        if (config == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        Map<String, Object> frontend = new LinkedHashMap<>(config.getFrontendRunningConfig() != null
                ? config.getFrontendRunningConfig()
                : Map.of());
        frontend.put("tool_guard_config", body != null ? new LinkedHashMap<>(body) : Map.of());
        config.setFrontendRunningConfig(frontend);
        String level = body != null && body.get("approval_level") != null
                ? String.valueOf(body.get("approval_level"))
                : (Boolean.FALSE.equals(body != null ? body.get("enabled") : null)
                    ? "OFF"
                    : ("OFF".equalsIgnoreCase(config.getApproval().getLevel()) ? "AUTO" : config.getApproval().getLevel()));
        config.getApproval().setLevel(normalizeApprovalLevel(level));
        configManager.save();
        multiAgentManager.reload(id);
        return toolGuard(agentId);
    }

    @GetMapping("/config/security/tool-guard/builtin-rules")
    public Mono<ResponseEntity<?>> builtinToolGuardRules() {
        return Mono.just(ResponseEntity.ok(List.of(Map.of(
                "id", "agentscope-execute-approval",
                "category", "execution",
                "severity", "medium",
                "patterns", List.of("execute"),
                "exclude_patterns", List.of(),
                "description", "AgentScope execute runs only inside the configured sandbox and follows the Agent approval level.",
                "remediation", "Use SMART or STRICT approval for interactive work; configure unattended jobs explicitly."))));
    }

    @GetMapping("/config/security/audit-events")
    public Mono<ResponseEntity<?>> auditEvents(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @org.springframework.security.core.annotation.AuthenticationPrincipal EnterprisePrincipal principal,
                                               @RequestParam(value = "session_id", required = false) String sessionId,
                                               @RequestParam(value = "tool_name", required = false) String toolName,
                                               @RequestParam(value = "decision", required = false) String decision,
                                               @RequestParam(value = "limit", defaultValue = "100") int limit) {
        String effectiveAgentId = agentAccessService.resolveAuditScope(principal, agentId);
        return Mono.just(ResponseEntity.ok(auditLogService.query(
                effectiveAgentId, sessionId, toolName, decision, limit)));
    }

    @GetMapping("/config/security/file-guard")
    public Mono<ResponseEntity<?>> fileGuard() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.fileGuard()));
    }

    @PutMapping("/config/security/file-guard")
    public Mono<ResponseEntity<?>> updateFileGuard(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(securitySettingsService.saveFileGuard(body)));
    }

    @GetMapping("/config/security/skill-scanner")
    public Mono<ResponseEntity<?>> skillScanner() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.skillScanner()));
    }

    @PutMapping("/config/security/skill-scanner")
    public Mono<ResponseEntity<?>> updateSkillScanner(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(securitySettingsService.saveSkillScanner(body)));
    }

    @GetMapping("/config/security/skill-scanner/blocked-history")
    public Mono<ResponseEntity<?>> blockedSkillHistory() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.blockedHistory()));
    }

    @DeleteMapping("/config/security/skill-scanner/blocked-history")
    public Mono<ResponseEntity<?>> clearBlockedSkillHistory() {
        securitySettingsService.clearBlockedHistory();
        return Mono.just(ResponseEntity.ok(Map.of("cleared", true)));
    }

    @DeleteMapping("/config/security/skill-scanner/blocked-history/{index}")
    public Mono<ResponseEntity<?>> removeBlockedSkillEntry(@PathVariable int index) {
        return Mono.just(ResponseEntity.ok(Map.of("removed", securitySettingsService.removeBlockedEntry(index))));
    }

    @PostMapping("/config/security/skill-scanner/whitelist")
    public Mono<ResponseEntity<?>> addSkillScannerWhitelist(@RequestBody(required = false) Map<String, Object> body) {
        String skillName = body != null ? stringValue(body.get("skill_name"), "") : "";
        String contentHash = body != null ? stringValue(body.get("content_hash"), "") : "";
        Map<String, Object> entry = securitySettingsService.addWhitelist(skillName, contentHash);
        return Mono.just(ResponseEntity.ok(Map.of(
                "whitelisted", true,
                "skill_name", entry.get("skill_name")
        )));
    }

    @DeleteMapping("/config/security/skill-scanner/whitelist/{skillName}")
    public Mono<ResponseEntity<?>> removeSkillScannerWhitelist(@PathVariable String skillName) {
        return Mono.just(ResponseEntity.ok(Map.of("removed", securitySettingsService.removeWhitelist(skillName), "skill_name", skillName)));
    }

    @GetMapping("/config/security/allow-no-auth-hosts")
    public Mono<ResponseEntity<?>> allowNoAuthHosts() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.allowNoAuthHosts()));
    }

    @PutMapping("/config/security/allow-no-auth-hosts")
    public Mono<ResponseEntity<?>> updateAllowNoAuthHosts(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(securitySettingsService.saveAllowNoAuthHosts(body)));
    }

    @GetMapping("/local-models/config")
    public Mono<ResponseEntity<?>> localModelConfig() {
        return Mono.just(ResponseEntity.ok(localModelConfigPayload()));
    }

    @PutMapping("/local-models/config")
    public Mono<ResponseEntity<?>> updateLocalModelConfig(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> config = ollamaConfig();
            if (body != null) {
                if (body.containsKey("port")) config.put("port", body.get("port"));
                if (body.containsKey("generate_kwargs")) config.put("generate_kwargs", body.get("generate_kwargs"));
            }
            saveOllamaConfig(config);
            reloadAgentsUsingProvider("ollama");
            return ResponseEntity.ok(localModelConfigPayload());
        });
    }

    @GetMapping("/local-models/server")
    public Mono<ResponseEntity<?>> localModelServer(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(localServerStatus(agentId)))
                .subscribeOn(webBlockingScheduler());
    }

    @PostMapping("/local-models/server")
    public Mono<ResponseEntity<?>> selectLocalModel(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            Map<String, Object> status = localServerStatus(id);
            if (!Boolean.TRUE.equals(status.get("available"))) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", status.get("message")));
            }
            String modelId = body != null ? stringValue(body.get("model_id"), "") : "";
            if (modelId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "model_id is required"));
            }
            if (installedOllamaModel(ollamaModels(), modelId) == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error", "message", "Ollama model is not installed: " + modelId));
            }
            AgentConfig agentConfig = configManager.getConfig().getAgent(id);
            if (agentConfig == null) {
                return ResponseEntity.notFound().build();
            }
            agentConfig.setActiveModel("ollama:" + modelId);
            configManager.save();
            multiAgentManager.reload(id);
            return ResponseEntity.ok(Map.of(
                    "port", integerValue(ollamaConfig().get("port"), 11434),
                    "model_name", modelId
            ));
        }).subscribeOn(webBlockingScheduler());
    }

    @GetMapping("/local-models/models")
    public Mono<ResponseEntity<?>> localModels() {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(localModelList()))
                .subscribeOn(webBlockingScheduler());
    }

    @PostMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> localModelDownload(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String modelName = body != null ? stringValue(body.get("model_name"), "") : "";
            if (modelName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "model_name is required"));
            }
            synchronized (localModelDownload) {
                CompletableFuture<?> active = localModelDownloadFuture.get();
                if ((active != null && !active.isDone()) || isDownloadActive(localModelDownload)) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Another model download is already running"));
                }
                setLocalModelDownloadStatus(downloadStatus(
                        "pending", modelName, 0L, null, "ollama", null));
                startOllamaModelPull(modelName);
            }
            return ResponseEntity.ok(Map.of("status", "started", "message", "Model download started"));
        });
    }

    @GetMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> localModelDownloadStatus() {
        return Mono.just(ResponseEntity.ok(localModelDownloadSnapshot()));
    }

    @DeleteMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> cancelLocalModelDownload() {
        synchronized (localModelDownload) {
            CompletableFuture<?> future = localModelDownloadFuture.getAndSet(null);
            boolean cancelled = future != null && !future.isDone() && future.cancel(true);
            if (!cancelled) {
                return Mono.just(ResponseEntity.ok(Map.of(
                        "status", stringValue(localModelDownload.get("status"), "idle"),
                        "cancelled", false,
                        "message", "No active model download")));
            }
            localModelDownloadGeneration.incrementAndGet();
            String modelName = stringValue(localModelDownload.get("model_name"), "");
            String source = stringValue(localModelDownload.get("source"), "");
            setLocalModelDownloadStatus(downloadStatus("cancelled",
                    modelName.isBlank() ? null : modelName, 0L, null,
                    source.isBlank() ? null : source, null));
            return Mono.just(ResponseEntity.ok(Map.of(
                    "status", "cancelled",
                    "cancelled", true,
                    "message", "Model download cancelled")));
        }
    }

    @DeleteMapping("/local-models/models/{modelId}")
    public Mono<ResponseEntity<?>> deleteLocalModel(@PathVariable String modelId) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            String activeModel = "ollama:" + modelId;
            boolean inUse = configManager.getConfig().getAgents().values().stream()
                    .anyMatch(agent -> activeModel.equals(agent.getActiveModel()));
            if (inUse) {
                return ResponseEntity.status(409).body(Map.of(
                        "status", "error", "message", "Ollama model is active for at least one agent"));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/delete"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(Map.of("model", modelId))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                unregisterOllamaModel(modelId);
                return ResponseEntity.ok(Map.of("status", "deleted", "message", "Model deleted"));
            }
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", response.body()));
        }).subscribeOn(webBlockingScheduler());
    }

    private Map<String, Object> localModelConfigPayload() {
        Map<String, Object> config = ollamaConfig();
        return Map.of(
                "port", integerValue(config.get("port"), 11434)
        );
    }

    private Map<String, Object> localServerStatus(String agentId) {
        boolean available = ollamaAvailable();
        Map<String, Object> config = ollamaConfig();
        AgentConfig agent = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        String activeModel = agent != null ? stringValue(agent.getActiveModel(), "") : "";
        String selectedOllamaModel = activeModel.startsWith("ollama:")
                ? activeModel.substring("ollama:".length()) : null;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("available", available);
        status.put("installable", false);
        status.put("installed", available);
        status.put("port", integerValue(config.get("port"), 11434));
        status.put("model_name", selectedOllamaModel);
        status.put("message", available
                ? "Ollama is reachable" : "Ollama is not reachable at " + ollamaBaseUrl());
        return status;
    }

    private List<Map<String, Object>> localModelList() {
        Map<String, Long> downloaded = ollamaModels();
        LinkedHashMap<String, Map<String, Object>> models = new LinkedHashMap<>();
        for (String id : List.of("llama3.1", "qwen2.5", "deepseek-r1", "mistral", "phi3")) {
            Map.Entry<String, Long> installed = installedOllamaModel(downloaded, id);
            String effectiveId = installed != null ? installed.getKey() : id;
            models.put(effectiveId, localModelInfo(effectiveId, effectiveId,
                    installed != null ? installed.getValue() : 0L, installed != null));
        }
        for (Map.Entry<String, Long> installed : downloaded.entrySet()) {
            models.put(installed.getKey(), localModelInfo(
                    installed.getKey(), installed.getKey(), installed.getValue(), true));
        }
        return new ArrayList<>(models.values());
    }

    private Map.Entry<String, Long> installedOllamaModel(Map<String, Long> installed, String id) {
        if (installed.containsKey(id)) return Map.entry(id, installed.get(id));
        String latest = id + ":latest";
        return installed.containsKey(latest) ? Map.entry(latest, installed.get(latest)) : null;
    }

    private Map<String, Object> localModelInfo(String id, String name, long sizeBytes, boolean downloaded) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id);
        model.put("name", name);
        model.put("size_bytes", sizeBytes);
        model.put("downloaded", downloaded);
        model.put("source", "ollama");
        return model;
    }

    private Map<String, Long> ollamaModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of();
            }
            Object parsed = JsonUtils.getMapper().readValue(response.body(), Object.class);
            Object rawModels = parsed instanceof Map<?, ?> map ? map.get("models") : null;
            if (!(rawModels instanceof List<?> list)) {
                return Map.of();
            }
            Map<String, Long> result = new LinkedHashMap<>();
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> model) {
                    String name = stringValue(model.get("name"), "");
                    if (!name.isBlank()) result.put(name, longValue(model.get("size"), 0L));
                }
            }
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private boolean ollamaAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startOllamaModelPull(String modelName) {
        String source = "ollama";
        long generation = localModelDownloadGeneration.incrementAndGet();
        setLocalModelDownloadStatus(downloadStatus(
                "downloading", modelName, 0L, null, source, null));
        HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/pull"))
                .timeout(Duration.ofHours(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JsonUtils.toJson(Map.of("model", modelName, "stream", false))))
                .build();
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        localModelDownloadFuture.set(future);
        future.whenComplete((response, error) -> {
            if (generation != localModelDownloadGeneration.get()) {
                return;
            }
            localModelDownloadFuture.compareAndSet(future, null);
            if (error != null) {
                setLocalModelDownloadStatus(downloadStatus("failed", modelName, 0L, null, source,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                try {
                    registerOllamaModel(modelName);
                    setLocalModelDownloadStatus(downloadStatus(
                            "completed", modelName, 0L, null, source, null));
                } catch (Exception e) {
                    setLocalModelDownloadStatus(downloadStatus(
                            "failed", modelName, 0L, null, source, e.getMessage()));
                }
            } else {
                setLocalModelDownloadStatus(downloadStatus(
                        "failed", modelName, 0L, null, source, response.body()));
            }
        });
    }

    private void setLocalModelDownloadStatus(Map<String, Object> status) {
        synchronized (localModelDownload) {
            localModelDownload.clear();
            localModelDownload.putAll(status);
        }
    }

    private Map<String, Object> localModelDownloadSnapshot() {
        synchronized (localModelDownload) {
            return localModelDownload.isEmpty()
                    ? idleDownload(null) : new LinkedHashMap<>(localModelDownload);
        }
    }

    private Map<String, Object> idleDownload(String modelName) {
        return downloadStatus("idle", modelName, 0L, null, null, null);
    }

    private Map<String, Object> downloadStatus(String status, String modelName, long downloadedBytes,
                                               Long totalBytes, String source, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("model_name", modelName);
        result.put("downloaded_bytes", downloadedBytes);
        result.put("total_bytes", totalBytes);
        result.put("speed_bytes_per_sec", 0L);
        result.put("source", source);
        result.put("error", error);
        result.put("local_path", null);
        return result;
    }

    private boolean isDownloadActive(Map<String, Object> state) {
        String status = stringValue(state.get("status"), "");
        return "pending".equals(status) || "downloading".equals(status) || "canceling".equals(status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ollamaConfig() {
        Object raw = providerConfigs().get("ollama");
        Map<String, Object> config = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        String baseUrl = stringValue(config.get("base_url"), "");
        if (baseUrl.isBlank()) {
            config.put("base_url", "http://127.0.0.1:" + integerValue(config.get("port"), 11434));
        }
        config.putIfAbsent("port", portFromBaseUrl(stringValue(config.get("base_url"), "")));
        return config;
    }

    private void registerOllamaModel(String modelName) {
        Map<String, Object> config = ollamaConfig();
        List<Object> models = new ArrayList<>();
        Object raw = config.get("models");
        if (raw instanceof List<?> list) models.addAll(list);
        boolean exists = models.stream().anyMatch(item -> modelName.equals(configuredModelId(item)));
        if (!exists) {
            models.add(modelName);
            config.put("models", models);
            saveOllamaConfig(config);
        }
    }

    private void unregisterOllamaModel(String modelName) {
        Map<String, Object> config = ollamaConfig();
        Object raw = config.get("models");
        if (!(raw instanceof List<?> list)) return;
        List<Object> models = list.stream()
                .filter(item -> !modelName.equals(configuredModelId(item)))
                .map(item -> (Object) item)
                .toList();
        config.put("models", models);
        saveOllamaConfig(config);
    }

    private String configuredModelId(Object item) {
        if (item instanceof Map<?, ?> model) return stringValue(model.get("id"), "");
        return stringValue(item, "");
    }

    private synchronized void saveOllamaConfig(Map<String, Object> config) {
        int port = integerValue(config.get("port"), 11434);
        config.put("port", port);
        config.put("base_url", "http://127.0.0.1:" + port);
        config.put("enabled", true);
        Map<String, Object> providers = providerConfigs();
        providers.put("ollama", config);
        JsonUtils.save(providerConfigFile(), configManager.protectSecrets(providers));
        providerManager.refreshProviderConfig();
    }

    private void reloadAgentsUsingProvider(String providerId) {
        String prefix = providerId + ":";
        configManager.getConfig().getAgents().forEach((id, agent) -> {
            if (stringValue(agent.getActiveModel(), "").startsWith(prefix)) {
                multiAgentManager.reload(id);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> providerConfigs() {
        Map<String, Object> stored = JsonUtils.loadAsMap(providerConfigFile());
        Object runtime = configManager.unprotectSecrets(stored);
        return runtime instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private Path providerConfigFile() {
        return configManager.resolveHomeDir().resolve("providers.json");
    }

    private String ollamaBaseUrl() {
        return stringValue(ollamaConfig().get("base_url"), "http://127.0.0.1:11434").replaceAll("/+$", "");
    }

    private int portFromBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getPort() > 0 ? uri.getPort() : 11434;
        } catch (Exception ignored) {
            return 11434;
        }
    }

    private int integerValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @GetMapping("/market/providers")
    public Mono<ResponseEntity<?>> marketProviders() {
        return Mono.just(ResponseEntity.ok(List.of(Map.of(
                "key", "qwenpaw",
                "label", "melonPaw Local",
                "available", true,
                "reason", null,
                "supports_browse", true
        ))));
    }

    @GetMapping("/market/categories")
    public Mono<ResponseEntity<?>> marketCategories() {
        return Mono.just(ResponseEntity.ok(List.of(
                Map.of("id", "builtin", "label", "Built-in"),
                Map.of("id", "pool", "label", "Skill Pool")
        )));
    }

    @PostMapping("/market/search")
    public Mono<ResponseEntity<?>> marketSearch(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            String query = stringValue(body != null ? body.get("query") : null, "").toLowerCase(Locale.ROOT);
            String category = stringValue(body != null ? body.get("category") : null, "");
            int limit = Math.max(1, Math.min(integerValue(body != null ? body.get("limit") : null, 10), 50));
            int page = marketPage(body);
            List<Map<String, Object>> all = localMarketResults(query, category);
            int from = Math.min((page - 1) * limit, all.size());
            int to = Math.min(from + limit, all.size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("results", all.subList(from, to));
            result.put("errors", List.of());
            result.put("by_provider", Map.of("qwenpaw", Map.of(
                    "has_more", to < all.size(),
                    "total", all.size()
            )));
            return ResponseEntity.ok(result);
        });
    }

    private List<Map<String, Object>> localMarketResults(String query, String category) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        if (category.isBlank() || "builtin".equals(category)) {
            for (Map<String, Object> skill : builtinSkillService.listBuiltinSources()) {
                Map<String, Object> item = marketSkill(skill, "builtin", "builtin://" + stringValue(skill.get("name"), ""));
                if (matchesMarketQuery(item, query)) results.add(item);
            }
        }
        if (category.isBlank() || "pool".equals(category)) {
            for (Map<String, Object> skill : skillService.listPoolSkills()) {
                Map<String, Object> item = marketSkill(skill, "pool", "pool://" + stringValue(skill.get("name"), ""));
                if (matchesMarketQuery(item, query)) results.add(item);
            }
        }
        return results;
    }

    private Map<String, Object> marketSkill(Map<String, Object> skill, String kind, String sourceUrl) {
        String name = stringValue(skill.get("name"), "");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", "qwenpaw");
        item.put("slug", kind + "/" + name);
        item.put("name", name);
        item.put("description", stringValue(skill.get("description"), null));
        item.put("source_url", sourceUrl);
        item.put("version", stringValue(skill.get("version_text"), null));
        item.put("author", null);
        item.put("icon_url", null);
        item.put("stats", Map.of("kind", kind));
        return item;
    }

    private boolean matchesMarketQuery(Map<String, Object> item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return stringValue(item.get("name"), "").toLowerCase(Locale.ROOT).contains(query)
                || stringValue(item.get("description"), "").toLowerCase(Locale.ROOT).contains(query);
    }

    private int marketPage(Map<String, Object> body) {
        Object raw = body != null ? body.get("provider_pages") : null;
        if (raw instanceof Map<?, ?> pages) {
            return Math.max(1, integerValue(pages.get("qwenpaw"), 1));
        }
        return 1;
    }

    @GetMapping("/agent-stats")
    public Mono<ResponseEntity<Object>> agentStats(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestParam(value = "start_date", required = false) String startDate,
                                                   @RequestParam(value = "end_date", required = false) String endDate) {
        String id = AgentRequestSupport.agentId(agentId);
        return Mono.fromCallable(() -> ResponseEntity.ok((Object) agentStatsService.getSummary(id, startDate, endDate)))
                .subscribeOn(webBlockingScheduler());
    }

    @GetMapping("/")
    public Mono<ResponseEntity<?>> root() {
        return Mono.just(ResponseEntity.ok(Map.of("ok", true, "service", "melonpaw-java")));
    }

    @PostMapping("/commands/check")
    public Mono<ResponseEntity<?>> checkCommand(@RequestBody(required = false) Map<String, Object> body) {
        String text = body != null ? stringValue(body.get("text"), "") : "";
        boolean control = text.startsWith("/") && (text.startsWith("/approve") || text.startsWith("/deny"));
        return Mono.just(ResponseEntity.ok(Map.of("is_control_command", control, "command_token", control ? text.split("\\s+")[0] : "")));
    }

    @PostMapping("/approval/approve")
    public Mono<ResponseEntity<?>> approveCommand(@RequestBody(required = false) Map<String, Object> body,
                                                  @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                                  @org.springframework.security.core.annotation.AuthenticationPrincipal EnterprisePrincipal principal) {
        String sessionId = body != null ? stringValue(body.get("session_id"), "default") : "default";
        String requestId = body != null ? stringValue(body.get("request_id"), "") : "";
        String scope = body != null ? stringValue(body.get("scope"), "once") : "once";
        boolean elevated = principal.hasAnyPermission(APPROVALS_DECIDE, AGENTS_MANAGE);
        boolean accepted = approvalService.decidePendingApproval(agentId, sessionId, requestId,
                principal.id(), elevated, true, scope);
        return Mono.just(ResponseEntity.ok(Map.of("success", accepted, "message", accepted ? "approved" : "approval not found")));
    }

    @PostMapping("/approval/deny")
    public Mono<ResponseEntity<?>> denyCommand(@RequestBody(required = false) Map<String, Object> body,
                                               @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                               @org.springframework.security.core.annotation.AuthenticationPrincipal EnterprisePrincipal principal) {
        String sessionId = body != null ? stringValue(body.get("session_id"), "default") : "default";
        String requestId = body != null ? stringValue(body.get("request_id"), "") : "";
        boolean elevated = principal.hasAnyPermission(APPROVALS_DECIDE, AGENTS_MANAGE);
        boolean accepted = approvalService.decidePendingApproval(agentId, sessionId, requestId,
                principal.id(), elevated, false);
        return Mono.just(ResponseEntity.ok(Map.of("success", accepted, "message", accepted ? "denied" : "approval not found")));
    }

    @GetMapping("/agent/health")
    public Mono<ResponseEntity<?>> agentHealth() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "ok")));
    }

    private String normalizeApprovalLevel(String value) {
        String normalized = value == null ? "AUTO" : value.trim().toUpperCase();
        return switch (normalized) {
            case "OFF", "AUTO", "SMART", "STRICT", "RISK_BASED" -> normalized;
            default -> "RISK_BASED";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> storedToolGuard(AgentConfig config) {
        if (config == null || config.getFrontendRunningConfig() == null) return Map.of();
        Object raw = config.getFrontendRunningConfig().get("tool_guard_config");
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private reactor.core.scheduler.Scheduler webBlockingScheduler() {
        return executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler();
    }
}
