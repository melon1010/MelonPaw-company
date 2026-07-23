package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.app.service.FileGuardService;
import com.melon.app.service.WorkspaceMemoryService;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.config.LightContextConfig;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.OutboundNetworkPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.melon.app.auth.EnterprisePermissions.SYSTEM_MANAGE;

/**
 * 工作区 API. 对应 Python /api/workspace.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);
    private static final int MAX_PERSONAL_DOCUMENT_BYTES = 1024 * 1024;
    static final long MAX_WORKSPACE_UPLOAD_BYTES = 100L * 1024L * 1024L;
    static final long MAX_TRANSCRIPTION_UPLOAD_BYTES = 25L * 1024L * 1024L;
    static final int MAX_TRANSCRIPTION_RESPONSE_BYTES = 1024 * 1024;
    private static final int FILE_STREAM_BUFFER_BYTES = 64 * 1024;
    private static final Set<String> PERSONAL_WORKSPACE_DOCUMENTS = Set.of(
            "AGENTS.md", "BOOTSTRAP.md", "HEARTBEAT.md", "MEMORY.md", "PROFILE.md", "SOUL.md"
    );

    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final MultiAgentManager multiAgentManager;
    private final FileGuardService fileGuardService;
    private final EnterpriseAgentAccessService agentAccessService;
    private final WorkspaceMemoryService workspaceMemoryService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WorkspaceController(ConfigManager configManager, WorkspaceManager workspaceManager,
                               MultiAgentManager multiAgentManager, FileGuardService fileGuardService,
                               EnterpriseAgentAccessService agentAccessService,
                               WorkspaceMemoryService workspaceMemoryService) {
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
        this.multiAgentManager = multiAgentManager;
        this.fileGuardService = fileGuardService;
        this.agentAccessService = agentAccessService;
        this.workspaceMemoryService = workspaceMemoryService;
    }

    /**
     * 获取工作区信息 (路径、AGENTS.md 内容、目录列表).
     */
    @GetMapping
    public Mono<ResponseEntity<?>> getWorkspace(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            try {
                String id = AgentRequestSupport.agentId(agentId);
                AgentConfig agentConfig = configManager.getConfig().getAgent(id);
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir(id);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("path", workspaceResponsePath(id, workspaceDir, principal));

                // Read AGENTS.md
                Path agentsMd = resolveInside(workspaceDir, "AGENTS.md");
                if (Files.isRegularFile(agentsMd, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    result.put("agents_md", Files.readString(agentsMd));
                } else {
                    result.put("agents_md", "");
                }

                // List top-level entries
                if (Files.exists(workspaceDir)) {
                    try (var stream = Files.list(workspaceDir)) {
                        result.put("entries", stream
                                .map(p -> p.getFileName().toString())
                                .sorted()
                                .toList());
                    }
                } else {
                    result.put("entries", java.util.List.of());
                }

                return ResponseEntity.ok(result);
            } catch (Exception e) {
                log.error("Failed to get workspace info", e);
                throw new IllegalStateException("Failed to read workspace information", e);
            }
        });
    }

    /**
     * 更新工作区 (主要更新 AGENTS.md 内容).
     */
    @PutMapping
    public Mono<ResponseEntity<?>> updateWorkspace(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody Map<String, String> body,
                                                   @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            try {
                String id = AgentRequestSupport.agentId(agentId);
                AgentConfig agentConfig = configManager.getConfig().getAgent(id);
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                String newDir = body.get("path");
                if (newDir != null && !newDir.isBlank()
                        && (principal == null || !principal.hasPermission(SYSTEM_MANAGE))) {
                    return ResponseEntity.status(403).body(Map.of(
                            "detail", "Only a system administrator can assign a host workspace path"));
                }

                Path workspaceDir = configManager.resolveWorkspaceDir(id);
                Files.createDirectories(workspaceDir);

                // Update AGENTS.md if provided
                String agentsMd = body.get("agents_md");
                if (agentsMd != null) {
                    Path agentsMdFile = resolveInside(workspaceDir, "AGENTS.md");
                    Files.writeString(agentsMdFile, agentsMd);
                    log.info("AGENTS.md updated at {}", agentsMdFile);
                }

                // Update workspace dir if provided
                if (newDir != null && !newDir.isBlank()) {
                    agentConfig.setWorkspaceDir(configManager.validateManagedWorkspacePath(newDir).toString());
                    configManager.save();
                    log.info("Workspace dir updated to: {}", newDir);
                }

                return ResponseEntity.ok(Map.of("status", "updated"));
            } catch (IOException e) {
                log.error("Failed to update workspace", e);
                throw new IllegalStateException("Failed to update workspace", e);
            }
        });
    }

    /**
     * 初始化工作区 (创建目录结构和默认文件).
     */
    @PostMapping("/init")
    public Mono<ResponseEntity<?>> initWorkspace(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            try {
                String id = AgentRequestSupport.agentId(agentId);
                AgentConfig agentConfig = configManager.getConfig().getAgent(id);
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir(id);
                workspaceManager.initWorkspace(workspaceDir);

                return ResponseEntity.ok(Map.of(
                        "status", "initialized",
                        "path", workspaceResponsePath(id, workspaceDir, principal)));
            } catch (Exception e) {
                log.error("Failed to init workspace", e);
                throw new IllegalStateException("Failed to initialize workspace", e);
            }
        });
    }

    @GetMapping("/running-config")
    public Mono<ResponseEntity<?>> getRunningConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            AgentConfig agentConfig = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
            if (agentConfig == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            if (agentConfig.getFrontendRunningConfig() != null) {
                result.putAll(sanitizeFrontendRunningConfig(agentConfig.getFrontendRunningConfig()));
            }
            result.put("max_iters", agentConfig.getRunning().getMaxIters());
            result.put("auto_continue_on_text_only", agentConfig.getRunning().isAutoContinueOnTextOnly());
            result.put("shell_command_timeout", agentConfig.getRunning().getShellCommandTimeout());
            result.put("llm_retry_enabled", agentConfig.getRunning().isLlmRetryEnabled());
            result.put("llm_max_retries", agentConfig.getRunning().getLlmMaxRetries());
            result.put("llm_backoff_base", agentConfig.getRunning().getLlmBackoffBase());
            result.put("llm_backoff_cap", agentConfig.getRunning().getLlmBackoffCap());
            result.put("llm_max_concurrent", agentConfig.getRunning().getLlmMaxConcurrent());
            result.put("llm_max_qpm", agentConfig.getRunning().getLlmMaxQpm());
            result.put("llm_rate_limit_pause", agentConfig.getRunning().getLlmRateLimitPause());
            result.put("llm_rate_limit_jitter", agentConfig.getRunning().getLlmRateLimitJitter());
            result.put("llm_acquire_timeout", agentConfig.getRunning().getLlmAcquireTimeout());
            result.putIfAbsent("context_manager_backend", "light");
            result.putIfAbsent("loop", defaultLoopConfig());
            result.put("memory_manager_backend", normalizeMemoryBackend(agentConfig.getMemoryManagerBackend()));
            result.put("approval_level", agentConfig.getApproval().getLevel());
            result.put("light_context_config", lightContextPayload(agentConfig));
            result.putIfAbsent("auto_title_config", Map.of("enabled", true));
            return ResponseEntity.ok(result);
        });
    }

    @PutMapping("/running-config")
    public Mono<ResponseEntity<?>> updateRunningConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            AgentConfig agentConfig = configManager.getConfig().getAgent(id);
            if (agentConfig == null) {
                return ResponseEntity.notFound().build();
            }
            if (body != null) {
                applyRunningConfig(agentConfig, body);
                agentConfig.setFrontendRunningConfig(sanitizeFrontendRunningConfig(body));
            }
            String approvalLevel = body != null && body.get("approval_level") != null
                    ? String.valueOf(body.get("approval_level"))
                    : "";
            if (!approvalLevel.isBlank()) {
                agentConfig.getApproval().setLevel(normalizeApprovalLevel(approvalLevel));
            }
            configManager.save();
            multiAgentManager.reload(id);
            return ResponseEntity.ok(body != null ? body : Map.of());
        });
    }

    private String normalizeApprovalLevel(String value) {
        String normalized = value == null ? "AUTO" : value.trim().toUpperCase();
        return switch (normalized) {
            case "OFF", "AUTO", "SMART", "STRICT", "RISK_BASED" -> normalized;
            default -> "RISK_BASED";
        };
    }

    private void applyRunningConfig(AgentConfig agentConfig, Map<String, Object> body) {
        if (body.get("max_iters") != null) {
            agentConfig.getRunning().setMaxIters((int) numberValue(body.get("max_iters"), agentConfig.getRunning().getMaxIters()));
        }
        if (body.get("auto_continue_on_text_only") != null) {
            agentConfig.getRunning().setAutoContinueOnTextOnly(booleanValue(body.get("auto_continue_on_text_only"), agentConfig.getRunning().isAutoContinueOnTextOnly()));
        }
        if (body.get("shell_command_timeout") != null) {
            agentConfig.getRunning().setShellCommandTimeout(numberValue(body.get("shell_command_timeout"), agentConfig.getRunning().getShellCommandTimeout()));
        }
        if (body.get("llm_retry_enabled") != null) {
            agentConfig.getRunning().setLlmRetryEnabled(booleanValue(body.get("llm_retry_enabled"), agentConfig.getRunning().isLlmRetryEnabled()));
        }
        if (body.get("llm_max_retries") != null) {
            agentConfig.getRunning().setLlmMaxRetries((int) numberValue(body.get("llm_max_retries"), agentConfig.getRunning().getLlmMaxRetries()));
        }
        if (body.get("llm_backoff_base") != null) {
            agentConfig.getRunning().setLlmBackoffBase(numberValue(body.get("llm_backoff_base"), agentConfig.getRunning().getLlmBackoffBase()));
        }
        if (body.get("llm_backoff_cap") != null) {
            agentConfig.getRunning().setLlmBackoffCap(numberValue(body.get("llm_backoff_cap"), agentConfig.getRunning().getLlmBackoffCap()));
        }
        if (body.get("llm_max_concurrent") != null) {
            agentConfig.getRunning().setLlmMaxConcurrent((int) numberValue(body.get("llm_max_concurrent"), agentConfig.getRunning().getLlmMaxConcurrent()));
        }
        if (body.get("llm_max_qpm") != null) {
            agentConfig.getRunning().setLlmMaxQpm((int) numberValue(body.get("llm_max_qpm"), agentConfig.getRunning().getLlmMaxQpm()));
        }
        if (body.get("llm_rate_limit_pause") != null) {
            agentConfig.getRunning().setLlmRateLimitPause(numberValue(body.get("llm_rate_limit_pause"), agentConfig.getRunning().getLlmRateLimitPause()));
        }
        if (body.get("llm_rate_limit_jitter") != null) {
            agentConfig.getRunning().setLlmRateLimitJitter(numberValue(body.get("llm_rate_limit_jitter"), agentConfig.getRunning().getLlmRateLimitJitter()));
        }
        if (body.get("llm_acquire_timeout") != null) {
            agentConfig.getRunning().setLlmAcquireTimeout(numberValue(body.get("llm_acquire_timeout"), agentConfig.getRunning().getLlmAcquireTimeout()));
        }
        if (body.get("memory_manager_backend") != null) {
            agentConfig.setMemoryManagerBackend(normalizeMemoryBackend(
                    String.valueOf(body.get("memory_manager_backend"))));
        }
        if (body.get("light_context_config") instanceof Map<?, ?> lightRaw) {
            Map<String, Object> light = toStringMap(lightRaw);
            LightContextConfig lightConfig = JsonUtils.getMapper().convertValue(light, LightContextConfig.class);
            if (!light.containsKey("context_compact_config")) {
                lightConfig.setContextCompactConfig(agentConfig.getContextCompact());
            }
            normalizeLightContext(agentConfig, lightConfig);
            agentConfig.setLightContextConfig(lightConfig);
            agentConfig.setContextCompact(lightConfig.getContextCompactConfig());
            if (light.get("context_compact_config") instanceof Map<?, ?> compactRaw) {
                Map<String, Object> compact = toStringMap(compactRaw);
                if (compact.get("enabled") != null) {
                    agentConfig.getContextCompact().setEnabled(booleanValue(compact.get("enabled"), agentConfig.getContextCompact().isEnabled()));
                }
                if (compact.get("compact_threshold_ratio") != null) {
                    agentConfig.getContextCompact().setCompactThresholdRatio(numberValue(compact.get("compact_threshold_ratio"), agentConfig.getContextCompact().getCompactThresholdRatio()));
                }
                if (compact.get("reserve_threshold_ratio") != null) {
                    agentConfig.getContextCompact().setReserveThresholdRatio(numberValue(compact.get("reserve_threshold_ratio"), agentConfig.getContextCompact().getReserveThresholdRatio()));
                }
            }
        }
    }

    private Map<String, Object> lightContextPayload(AgentConfig agentConfig) {
        LightContextConfig light = agentConfig.getLightContextConfig() != null
                ? agentConfig.getLightContextConfig()
                : new LightContextConfig();
        normalizeLightContext(agentConfig, light);
        Map<String, Object> payload = JsonUtils.getMapper().convertValue(light,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        payload.remove("dialog_path");
        if (payload.get("tool_result_pruning_config") instanceof Map<?, ?> rawPruning) {
            Map<String, Object> pruning = toStringMap(rawPruning);
            pruning.remove("pruning_recent_n");
            pruning.remove("pruning_recent_msg_max_bytes");
            pruning.remove("offload_retention_days");
            pruning.remove("exempt_file_extensions");
            payload.put("tool_result_pruning_config", pruning);
        }
        return payload;
    }

    private void normalizeLightContext(AgentConfig agentConfig, LightContextConfig light) {
        if (light.getStrategy() == null || light.getStrategy().isBlank()) light.setStrategy("scroll");
        if (!"native".equals(light.getStrategy()) && !"scroll".equals(light.getStrategy())) light.setStrategy("scroll");
        if (light.getDialogPath() == null || light.getDialogPath().isBlank()) light.setDialogPath("dialog");
        if (light.getTokenCountEstimateDivisor() <= 0) light.setTokenCountEstimateDivisor(4.0);
        if (light.getContextCompactConfig() == null) light.setContextCompactConfig(agentConfig.getContextCompact());
        if (light.getToolResultPruningConfig() == null) light.setToolResultPruningConfig(new com.melon.core.config.ToolResultPruningConfig());
        if (light.getScrollConfig() == null) light.setScrollConfig(new com.melon.core.config.ScrollContextConfig());
        if (light.getScrollConfig().getDbFilename() == null || light.getScrollConfig().getDbFilename().isBlank()) {
            light.getScrollConfig().setDbFilename("history.db");
        }
    }

    private String normalizeMemoryBackend(String value) {
        return "none".equalsIgnoreCase(value) ? "none" : "file";
    }

    private Map<String, Object> sanitizeFrontendRunningConfig(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        result.put("context_manager_backend", "light");
        result.put("loop", sanitizeLoopConfig(source.get("loop")));
        Object autoTitleRaw = source.get("auto_title_config");
        boolean autoTitle = true;
        if (autoTitleRaw instanceof Map<?, ?> autoTitleMap) {
            autoTitle = booleanValue(autoTitleMap.get("enabled"), true);
        }
        result.put("auto_title_config", Map.of("enabled", autoTitle));
        return result;
    }

    private Map<String, Object> defaultLoopConfig() {
        return sanitizeLoopConfig(null);
    }

    private Map<String, Object> sanitizeLoopConfig(Object raw) {
        Map<?, ?> loop = raw instanceof Map<?, ?> map ? map : Map.of();

        Map<?, ?> iteration = loop.get("iteration") instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> safeIteration = new LinkedHashMap<>();
        safeIteration.put("enabled", booleanValue(iteration.get("enabled"), true));
        safeIteration.put("max_iterations", clampInt(iteration.get("max_iterations"), 6, 1, 500));

        Map<?, ?> doom = loop.get("doom_loop") instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> safeDoom = new LinkedHashMap<>();
        safeDoom.put("enabled", booleanValue(doom.get("enabled"), false));
        safeDoom.put("window_size", clampInt(doom.get("window_size"), 6, 2, 20));
        safeDoom.put("similarity_threshold", clampDouble(doom.get("similarity_threshold"), 0.9, 0, 1));
        List<Map<String, Object>> stages = new ArrayList<>();
        if (doom.get("stages") instanceof List<?> rawStages) {
            for (Object item : rawStages.stream().limit(10).toList()) {
                if (!(item instanceof Map<?, ?> stage)) continue;
                String action = "stop".equals(String.valueOf(stage.get("action"))) ? "stop" : "modify_prompt";
                Object promptValue = stage.containsKey("prompt") ? stage.get("prompt") : "";
                stages.add(Map.of(
                        "after", clampInt(stage.get("after"), 3, 1, 500),
                        "action", action,
                        "prompt", truncate(String.valueOf(promptValue), 2000)
                ));
            }
        }
        if (stages.isEmpty()) {
            stages.add(Map.of("after", 3, "action", "modify_prompt", "prompt", "Try a different approach."));
        }
        safeDoom.put("stages", stages);

        Map<?, ?> rubric = loop.get("rubric") instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> safeRubric = new LinkedHashMap<>();
        safeRubric.put("enabled", booleanValue(rubric.get("enabled"), true));
        Object rubricPrompt = rubric.containsKey("prompt") ? rubric.get("prompt") : "";
        safeRubric.put("prompt", truncate(String.valueOf(rubricPrompt), 4000));
        safeRubric.put("max_interventions", clampInt(rubric.get("max_interventions"), 3, 1, 10));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iteration", safeIteration);
        result.put("doom_loop", safeDoom);
        result.put("rubric", safeRubric);
        return result;
    }

    private int clampInt(Object value, int fallback, int min, int max) {
        int parsed = (int) numberValue(value, fallback);
        return Math.max(min, Math.min(max, parsed));
    }

    private double clampDouble(Object value, double fallback, double min, double max) {
        double parsed = numberValue(value, fallback);
        return Math.max(min, Math.min(max, parsed));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || "null".equals(value)) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @GetMapping("/audio-mode")
    public Mono<ResponseEntity<?>> getAudioMode(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "audio_mode", stringValue(transcriptionConfig(agentId).get("audio_mode"), "auto"))));
    }

    @PutMapping("/audio-mode")
    public Mono<ResponseEntity<?>> updateAudioMode(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = transcriptionConfig(agentId);
        String audioMode = stringValue(body != null ? body.get("audio_mode") : null, "auto");
        config.put("audio_mode", audioMode);
        saveTranscriptionConfig(agentId, config);
        return Mono.just(ResponseEntity.ok(Map.of("audio_mode", audioMode)));
    }

    @GetMapping("/transcription-providers")
    public Mono<ResponseEntity<?>> transcriptionProviders(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        Map<String, Object> config = transcriptionConfig(agentId);
        return Mono.just(ResponseEntity.ok(Map.of(
                "providers", transcriptionProvidersPayload(config),
                "configured_provider_id", stringValue(config.get("provider_id"), "")
        )));
    }

    @PutMapping("/transcription-provider")
    public Mono<ResponseEntity<?>> updateTranscriptionProvider(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = transcriptionConfig(agentId);
        if (body != null) {
            for (String key : List.of("provider_id", "base_url", "api_key", "model", "name")) {
                if (body.containsKey(key)) config.put(key, body.get(key));
            }
        }
        String providerId = stringValue(config.get("provider_id"), "");
        saveTranscriptionConfig(agentId, config);
        return Mono.just(ResponseEntity.ok(Map.of("provider_id", providerId)));
    }

    @GetMapping("/transcription-provider-type")
    public Mono<ResponseEntity<?>> transcriptionProviderType(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "transcription_provider_type", stringValue(
                        transcriptionConfig(agentId).get("transcription_provider_type"), "disabled")
        )));
    }

    @PutMapping("/transcription-provider-type")
    public Mono<ResponseEntity<?>> updateTranscriptionProviderType(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = transcriptionConfig(agentId);
        String type = stringValue(body != null ? body.get("transcription_provider_type") : null, "disabled");
        config.put("transcription_provider_type", type);
        saveTranscriptionConfig(agentId, config);
        return Mono.just(ResponseEntity.ok(Map.of("transcription_provider_type", type)));
    }

    @GetMapping("/local-whisper-status")
    public Mono<ResponseEntity<?>> localWhisperStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("available", false, "ffmpeg_installed", false, "whisper_installed", false)));
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> transcribeDisabled(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestPart("file") FilePart filePart) {
        Map<String, Object> config = transcriptionConfig(agentId);
        if (!"whisper_api".equals(stringValue(config.get("transcription_provider_type"), "disabled"))) {
            return Mono.just(transcriptionDisabledResponse());
        }
        Path path;
        try {
            path = Files.createTempFile("melon-transcribe-", ".audio");
        } catch (IOException e) {
            return Mono.error(e);
        }
        return storeFilePart(filePart, path, MAX_TRANSCRIPTION_UPLOAD_BYTES)
                .then(Mono.<ResponseEntity<?>>fromCallable(
                                () -> forwardExternalTranscription(path, filePart.filename(), config))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(PayloadTooLargeException.class,
                        error -> Mono.just(payloadTooLargeResponse(error)))
                .doFinally(signal -> {
                    deleteQuietly(path);
                });
    }

    private ResponseEntity<?> forwardExternalTranscription(Path file, String filename, Map<String, Object> transcriptionConfig) throws Exception {
        Map<String, Object> provider = transcriptionProviderConfig(transcriptionConfig);
        String baseUrl = stringValue(provider.get("base_url"), "");
        String apiKey = stringValue(provider.get("api_key"), "");
        String model = stringValue(provider.get("model"), "whisper-1");
        if (baseUrl.isBlank()) {
            return transcriptionDisabledResponse("External transcription base_url is not configured");
        }
        URI endpoint = OutboundNetworkPolicy.requirePublicHttpUri(transcriptionEndpoint(baseUrl));
        String boundary = "melon-" + java.util.UUID.randomUUID();
        HttpRequest.BodyPublisher body = transcriptionMultipartBody(file, filename, model, boundary);

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body);
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String responseBody;
        try (InputStream input = response.body()) {
            try {
                responseBody = readLimitedResponse(input, MAX_TRANSCRIPTION_RESPONSE_BYTES);
            } catch (RemoteResponseTooLargeException e) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "detail", Map.of(
                                "code", "TRANSCRIPTION_RESPONSE_TOO_LARGE",
                                "message", e.getMessage(),
                                "max_size_bytes", MAX_TRANSCRIPTION_RESPONSE_BYTES)
                ));
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ResponseEntity.status(response.statusCode()).body(Map.of(
                    "detail", Map.of("code", "TRANSCRIPTION_DISABLED", "message", responseBody)
            ));
        }
        Map<String, Object> parsed = JsonUtils.fromJson(responseBody, Map.class);
        String text = parsed != null ? stringValue(parsed.get("text"), "") : "";
        return ResponseEntity.ok(Map.of("text", text));
    }

    private HttpRequest.BodyPublisher transcriptionMultipartBody(
            Path file, String filename, String model, String boundary) throws IOException {
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + safeFilename(filename) + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(prefix.getBytes(StandardCharsets.UTF_8)),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofByteArray(suffix.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String readLimitedResponse(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new RemoteResponseTooLargeException(
                    "Transcription provider response exceeds " + maxBytes + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private ResponseEntity<?> transcriptionDisabledResponse() {
        return transcriptionDisabledResponse("Transcription is disabled. Configure an external Whisper-compatible provider.");
    }

    private ResponseEntity<?> transcriptionDisabledResponse(String message) {
        return ResponseEntity.status(503).body(Map.of(
                "detail", Map.of("code", "TRANSCRIPTION_DISABLED", "message", message)
        ));
    }

    private String transcriptionEndpoint(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        if (base.endsWith("/audio/transcriptions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/audio/transcriptions";
        }
        return base + "/v1/audio/transcriptions";
    }

    private List<Map<String, Object>> transcriptionProvidersPayload(Map<String, Object> transcriptionConfig) {
        List<Map<String, Object>> providers = new ArrayList<>();
        Map<String, Object> external = transcriptionProviderConfig(transcriptionConfig);
        if (!stringValue(external.get("base_url"), "").isBlank()) {
            providers.add(Map.of(
                    "id", "external",
                    "name", stringValue(external.get("name"), "External Whisper API"),
                    "available", true
            ));
        }
        Map<String, Object> openai = providerConfig("openai");
        if (!stringValue(openai.get("base_url"), "").isBlank() || !providerApiKey("openai", openai).isBlank()) {
            providers.add(Map.of(
                    "id", "openai",
                    "name", "OpenAI",
                    "available", !providerApiKey("openai", openai).isBlank()
            ));
        }
        return providers;
    }

    private Map<String, Object> transcriptionProviderConfig(Map<String, Object> transcriptionConfig) {
        String providerId = stringValue(transcriptionConfig.get("provider_id"), "external");
        Map<String, Object> result = new LinkedHashMap<>();
        if ("external".equals(providerId)) {
            result.put("base_url", transcriptionConfig.get("base_url"));
            result.put("api_key", transcriptionConfig.get("api_key"));
            result.put("model", stringValue(transcriptionConfig.get("model"), "whisper-1"));
            result.put("name", stringValue(transcriptionConfig.get("name"), "External Whisper API"));
            return result;
        }
        Map<String, Object> provider = providerConfig(providerId);
        result.put("base_url", stringValue(provider.get("base_url"), "https://api.openai.com/v1"));
        result.put("api_key", providerApiKey(providerId, provider));
        result.put("model", stringValue(transcriptionConfig.get("model"), "whisper-1"));
        result.put("name", providerId);
        return result;
    }

    private Map<String, Object> transcriptionConfig(String agentId) {
        Map<String, Object> config = new LinkedHashMap<>(JsonUtils.loadAsMap(transcriptionConfigFile(agentId)));
        config.putIfAbsent("audio_mode", "auto");
        config.putIfAbsent("transcription_provider_type", "disabled");
        config.putIfAbsent("provider_id", "");
        config.putIfAbsent("model", "whisper-1");
        return config;
    }

    private void saveTranscriptionConfig(String agentId, Map<String, Object> config) {
        JsonUtils.save(transcriptionConfigFile(agentId), config);
    }

    private Path transcriptionConfigFile(String agentId) {
        return configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId))
                .resolve(".melon")
                .resolve("transcription.json");
    }

    private Map<String, Object> providerConfig(String providerId) {
        Object raw = JsonUtils.loadAsMap(providerConfigFile()).get(providerId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }

    private Path providerConfigFile() {
        return configManager.resolveHomeDir().resolve("providers.json");
    }

    private String providerApiKey(String providerId, Map<String, Object> provider) {
        String apiKey = stringValue(provider.get("api_key"), "");
        if (!apiKey.isBlank()) {
            return apiKey;
        }
        String env = switch (providerId) {
            case "openai" -> "OPENAI_API_KEY";
            default -> "";
        };
        return env.isBlank() ? "" : stringValue(System.getenv(env), "");
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "audio.webm" : filename;
        return value.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    @GetMapping("/files")
    public Mono<ResponseEntity<?>> listWorkspaceFiles(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            return ResponseEntity.ok(listWorkingMarkdownFiles(
                    configManager.resolveWorkspaceDir(id), agentAccessService.isPersonalAgent(id)));
        });
    }

    @GetMapping("/files/{*fileName}")
    public Mono<ResponseEntity<?>> readWorkspaceFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                     @PathVariable String fileName) {
        return Mono.fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            Path workspace = configManager.resolveWorkspaceDir(id);
            String cleanName = cleanCapturedPath(fileName);
            Path file = resolveWorkingMarkdownFile(
                    workspace, cleanName, agentAccessService.isPersonalAgent(id));
            fileGuardService.assertAllowed(file);
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("content", Files.readString(file).strip()));
        });
    }

    @PutMapping("/files/{*fileName}")
    public Mono<ResponseEntity<?>> writeWorkspaceFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                      @PathVariable String fileName,
                                                      @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            Path workspace = configManager.resolveWorkspaceDir(id);
            Files.createDirectories(workspace);
            String cleanName = cleanCapturedPath(fileName);
            boolean personal = agentAccessService.isPersonalAgent(id);
            Path file = resolveWorkingMarkdownFile(workspace, cleanName, personal);
            fileGuardService.assertAllowed(file);
            if (Files.isSymbolicLink(file)) {
                throw new IllegalArgumentException("Workspace documents cannot be symbolic links");
            }
            String content = String.valueOf(body.getOrDefault("content", ""));
            if (personal && content.getBytes(StandardCharsets.UTF_8).length > MAX_PERSONAL_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("Workspace document exceeds the 1 MiB limit");
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("written", true));
        });
    }

    @GetMapping("/memory")
    public Mono<ResponseEntity<?>> listMemoryFiles(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(
                workspaceMemoryService.list(AgentRequestSupport.agentId(agentId))));
    }

    @GetMapping("/memory/{*memoryPath}")
    public Mono<ResponseEntity<?>> readMemoryFile(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable String memoryPath) {
        return Mono.fromCallable(() -> ResponseEntity.ok(workspaceMemoryService.read(
                AgentRequestSupport.agentId(agentId), cleanCapturedPath(memoryPath))));
    }

    @PutMapping("/memory/{*memoryPath}")
    public Mono<ResponseEntity<?>> writeMemoryFile(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @PathVariable String memoryPath,
            @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> ResponseEntity.ok(workspaceMemoryService.write(
                AgentRequestSupport.agentId(agentId), cleanCapturedPath(memoryPath),
                body == null ? "" : String.valueOf(body.getOrDefault("content", "")))));
    }

    @GetMapping("/download")
    public Mono<ResponseEntity<?>> downloadWorkspace(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            Path workspace = resolveWorkspace(agentId);
            Path zip = createWorkspaceArchive(workspace);
            boolean handedOff = false;
            try {
                long contentLength = Files.size(zip);
                Flux<DataBuffer> body = streamFileAndDelete(zip);
                ResponseEntity<?> response = ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"melonpaw-company-workspace.zip\"")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(contentLength)
                        .body(body);
                handedOff = true;
                return response;
            } finally {
                if (!handedOff) deleteQuietly(zip);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadWorkspaceFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @RequestPart("file") FilePart filePart) {
        Path workspace = resolveWorkspace(agentId);
        Path target = resolveInside(workspace, filePart.filename());
        fileGuardService.assertAllowed(target);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return Mono.error(new IllegalStateException("Failed to prepare upload directory", e));
        }
        return storeFilePart(filePart, target, MAX_WORKSPACE_UPLOAD_BYTES)
                .<ResponseEntity<?>>map(size -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "uploaded",
                        "path", filePart.filename(),
                        "size", size)))
                .onErrorResume(PayloadTooLargeException.class,
                        error -> Mono.just(payloadTooLargeResponse(error)));
    }

    Path createWorkspaceArchive(Path workspace) throws IOException {
        Path zip = Files.createTempFile("melonpaw-workspace-", ".zip");
        boolean complete = false;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            if (Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
                try (var stream = Files.walk(workspace)) {
                    var paths = stream
                            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .iterator();
                    while (paths.hasNext()) {
                        Path path = paths.next();
                        String entryName = workspace.relativize(path).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    }
                }
            }
            complete = true;
            return zip;
        } finally {
            if (!complete) deleteQuietly(zip);
        }
    }

    Flux<DataBuffer> streamFileAndDelete(Path file) {
        return DataBufferUtils.read(file, DefaultDataBufferFactory.sharedInstance, FILE_STREAM_BUFFER_BYTES)
                .concatWith(Flux.defer(() -> {
                    deleteQuietly(file);
                    return Flux.empty();
                }))
                .doOnError(error -> deleteQuietly(file))
                .doOnCancel(() -> deleteQuietly(file))
                .doFinally(signal -> deleteQuietly(file));
    }

    private Mono<Long> storeFilePart(FilePart filePart, Path target, long maxBytes) {
        long declared = filePart.headers().getContentLength();
        if (declared > maxBytes) {
            return Mono.error(new PayloadTooLargeException(maxBytes));
        }

        Path temporary;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".melon-upload-", ".tmp");
        } catch (IOException e) {
            return Mono.error(e);
        }

        AtomicLong total = new AtomicLong();
        Flux<DataBuffer> limited = filePart.content()
                .<DataBuffer>handle((buffer, sink) -> {
                    int chunkBytes = buffer.readableByteCount();
                    long current = total.get();
                    if (chunkBytes > maxBytes - current) {
                        DataBufferUtils.release(buffer);
                        sink.error(new PayloadTooLargeException(maxBytes));
                        return;
                    }
                    total.addAndGet(chunkBytes);
                    sink.next(buffer);
                })
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);

        return DataBufferUtils.write(limited, temporary,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)
                .then(Mono.fromCallable(() -> {
                    moveReplacing(temporary, target);
                    return total.get();
                }))
                .doFinally(signal -> deleteQuietly(temporary));
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ResponseEntity<?> payloadTooLargeResponse(PayloadTooLargeException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "detail", Map.of(
                        "code", "PAYLOAD_TOO_LARGE",
                        "message", error.getMessage(),
                        "max_size_bytes", error.maxBytes())
        ));
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temporary file: {}", path, e);
        }
    }

    private static final class PayloadTooLargeException extends RuntimeException {
        private final long maxBytes;

        private PayloadTooLargeException(long maxBytes) {
            super("Upload exceeds the " + maxBytes + " byte limit");
            this.maxBytes = maxBytes;
        }

        private long maxBytes() {
            return maxBytes;
        }
    }

    private static final class RemoteResponseTooLargeException extends IOException {
        private RemoteResponseTooLargeException(String message) {
            super(message);
        }
    }

    @GetMapping("/system-prompt-files")
    public Mono<ResponseEntity<?>> systemPromptFiles(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig agentConfig = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        return Mono.just(ResponseEntity.ok(agentConfig.getSystemPromptFiles()));
    }

    @PutMapping("/system-prompt-files")
    public Mono<ResponseEntity<?>> updateSystemPromptFiles(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                           @RequestBody List<String> files) {
        return Mono.fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            AgentConfig agentConfig = configManager.getConfig().getAgent(id);
            if (agentConfig == null) {
                return ResponseEntity.notFound().build();
            }
            List<String> cleanFiles = files == null ? List.of() : files.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> Path.of(value).getFileName().toString())
                    .filter(value -> value.endsWith(".md"))
                    .filter(value -> !agentAccessService.isPersonalAgent(id)
                            || PERSONAL_WORKSPACE_DOCUMENTS.contains(value))
                    .distinct()
                    .toList();
            agentConfig.setSystemPromptFiles(cleanFiles);
            configManager.save();
            multiAgentManager.reload(id);
            return ResponseEntity.ok(cleanFiles);
        });
    }

    private Path resolveWorkspace(String agentId) {
        return configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId));
    }

    private String workspaceResponsePath(String agentId, Path workspaceDir, EnterprisePrincipal principal) {
        return principal != null && principal.hasPermission(SYSTEM_MANAGE)
                ? workspaceDir.toString()
                : "workspace://" + agentId;
    }

    private Path resolveInside(Path root, String path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = normalizedRoot.resolve(path == null ? "" : path).normalize();
        if (!normalized.startsWith(normalizedRoot) || Files.isSymbolicLink(normalizedRoot)) {
            throw new IllegalArgumentException("Invalid path");
        }
        Path current = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Workspace paths cannot contain symbolic links");
            }
        }
        return normalized;
    }

    private String cleanCapturedPath(String path) {
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private List<Map<String, Object>> listWorkingMarkdownFiles(Path root, boolean personal) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(root)) return result;
        try (var stream = Files.list(root)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !personal
                            || PERSONAL_WORKSPACE_DOCUMENTS.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(this::lastModifiedMillis).reversed())
                    .toList()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("filename", path.getFileName().toString());
                info.put("path", path.getFileName().toString());
                info.put("size", Files.size(path));
                info.put("created_time", Files.getLastModifiedTime(path).toInstant().toString());
                info.put("modified_time", Files.getLastModifiedTime(path).toInstant().toString());
                result.add(info);
            }
        }
        return result;
    }

    private Path resolveWorkingMarkdownFile(Path workspace, String name, boolean personal) {
        String requested = name == null || name.isBlank() ? "AGENTS.md" : name;
        String fileName = Path.of(requested).getFileName().toString();
        if (!requested.equals(fileName)) {
            throw new IllegalArgumentException("Invalid workspace document name");
        }
        if (!fileName.endsWith(".md")) fileName += ".md";
        if (personal && !PERSONAL_WORKSPACE_DOCUMENTS.contains(fileName)) {
            throw new IllegalArgumentException("Unsupported personal workspace document");
        }
        Path file = workspace.resolve(fileName).normalize();
        if (!file.startsWith(workspace.normalize())) {
            throw new IllegalArgumentException("Invalid path");
        }
        resolveInside(workspace, fileName);
        return file;
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

}
