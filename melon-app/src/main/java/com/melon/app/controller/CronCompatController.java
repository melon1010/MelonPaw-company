package com.melon.app.controller;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.auth.EnterpriseIdentityStore;
import com.melon.app.cron.CronExecutor;
import com.melon.app.cron.CronManager;
import com.melon.app.cron.CronSchedulingService;
import com.melon.app.service.InboxStore;
import com.melon.channels.ChannelConfigService;
import com.melon.core.config.ConfigManager;
import com.melon.core.cron.CronJobStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

/**
 * melonPaw frontend-compatible cron API aliases.
 */
@RestController
@RequestMapping("/api/cron")
public class CronCompatController {

    private final ConfigManager configManager;
    private final CronSchedulingService schedulingService;
    private final CronExecutor cronExecutor;
    private final ChannelConfigService channelConfigService;
    private final InboxStore inboxStore;
    private final EnterpriseIdentityStore identityStore;

    public CronCompatController(ConfigManager configManager,
                                CronSchedulingService schedulingService,
                                 CronExecutor cronExecutor,
                                 ChannelConfigService channelConfigService,
                                 InboxStore inboxStore,
                                 EnterpriseIdentityStore identityStore) {
        this.configManager = configManager;
        this.schedulingService = schedulingService;
        this.cronExecutor = cronExecutor;
        this.channelConfigService = channelConfigService;
        this.inboxStore = inboxStore;
        this.identityStore = identityStore;
    }

    @GetMapping("/jobs")
    public Mono<ResponseEntity<?>> listJobs(@RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> jobs = loadJobs(agentId);
            String effectiveAgent = AgentRequestSupport.agentId(agentId);
            schedulingService.syncAll(effectiveAgent, workspaceDir(effectiveAgent), jobs);
            return ResponseEntity.ok(jobs);
        });
    }

    @PostMapping("/jobs")
    public Mono<ResponseEntity<?>> createJob(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                             @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            String effectiveAgent = AgentRequestSupport.agentId(agentId);
            Map<String, Object> candidate = new LinkedHashMap<>(body);
            stampOwner(candidate, principal);
            Map<String, Object> job = schedulingService.create(
                    effectiveAgent, workspaceDir(effectiveAgent), candidate);
            return ResponseEntity.ok(job);
        });
    }

    @GetMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> getJob(@PathVariable String id,
                                          @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> view = new LinkedHashMap<>(job);
            view.put("state", stateFor(agentId, job));
            view.put("history", historyFor(job));
            return ResponseEntity.ok(view);
        });
    }

    @PutMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> replaceJob(@PathVariable String id, @RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                               @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            String effectiveAgent = AgentRequestSupport.agentId(agentId);
            Map<String, Object> previous = findJob(effectiveAgent, id);
            if (previous == null) return ResponseEntity.notFound().build();
            String owner = ownerOf(previous, principal);
            Map<String, Object> candidate = new LinkedHashMap<>(body);
            candidate.put("owner_user_id", owner);
            normalizeDispatchOwner(candidate, owner);
            Map<String, Object> updated = schedulingService.replace(
                    effectiveAgent, workspaceDir(effectiveAgent), id, candidate);
            return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
        });
    }

    @DeleteMapping("/jobs/{id}")
    public Mono<ResponseEntity<?>> deleteJob(@PathVariable String id,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            String effectiveAgent = AgentRequestSupport.agentId(agentId);
            boolean deleted = schedulingService.delete(effectiveAgent, workspaceDir(effectiveAgent), id);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        });
    }

    @PostMapping("/jobs/{id}/pause")
    public Mono<ResponseEntity<?>> pauseJob(@PathVariable String id,
                                            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return setEnabled(agentId, id, false);
    }

    @PostMapping("/jobs/{id}/resume")
    public Mono<ResponseEntity<?>> resumeJob(@PathVariable String id,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return setEnabled(agentId, id, true);
    }

    @PostMapping("/jobs/{id}/run")
    public Mono<ResponseEntity<?>> runJob(@PathVariable String id,
                                          @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            if (booleanValue(job.getOrDefault("enabled", true), true) == false) {
                return ResponseEntity.status(409).body(Map.of("detail", "Cron job is disabled"));
            }
            String prompt = schedulingService.promptValue(job);
            if (prompt.isBlank()) {
                return ResponseEntity.status(409).body(Map.of("detail", "Cron job prompt is empty"));
            }
            String runAt = Instant.now().toString();
            String jobId = String.valueOf(job.get("id"));
            String runId = UUID.randomUUID().toString();
            markRun(agentId, id, runAt, "manual", "running", null);
            if (booleanValue(job.getOrDefault("save_result_to_inbox", true), true)) {
                try {
                    inboxStore.createTrace(runId, Map.of(
                            "job_id", jobId,
                            "job_name", stringValue(job.get("name"), "Cron job")));
                } catch (RuntimeException e) {
                    markRun(agentId, id, runAt, "manual", "error", e.getMessage());
                    throw e;
                }
            }
            try {
                cronExecutor.injectDispatchedMessage(AgentRequestSupport.agentId(agentId), prompt, runId,
                        schedulingService.dispatch(job),
                        reply -> {
                            markRun(agentId, id, runAt, "manual", "success", null);
                            appendManualInboxEvent(agentId, job, runId, cleanText(reply), null);
                        },
                        err -> {
                            markRun(agentId, id, runAt, "manual", "error", err.getMessage());
                            appendManualInboxEvent(agentId, job, runId, "", err);
                        });
            } catch (RuntimeException e) {
                markRun(agentId, id, runAt, "manual", "error", e.getMessage());
                if (booleanValue(job.getOrDefault("save_result_to_inbox", true), true)) {
                    inboxStore.finalizeTrace(runId, "error", e.getMessage());
                }
                throw e;
            }
            return ResponseEntity.ok(Map.of(
                    "started", true,
                    "job_id", jobId,
                    "run_at", runAt
            ));
        });
    }

    @GetMapping("/jobs/{id}/state")
    public Mono<ResponseEntity<?>> jobState(@PathVariable String id,
                                            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(stateFor(agentId, job));
        });
    }

    @GetMapping("/jobs/{id}/history")
    public Mono<ResponseEntity<?>> history(@PathVariable String id,
                                           @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> job = findJob(agentId, id);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(historyFor(job));
        });
    }

    @GetMapping("/dispatch-targets")
    public Mono<ResponseEntity<?>> dispatchTargets(@RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId) {
        return Mono.fromCallable(() -> {
            Map<String, Map<String, Object>> channels = channelConfigService.list(AgentRequestSupport.agentId(agentId));
            List<String> enabled = channels.entrySet().stream()
                    .filter(entry -> booleanValue(entry.getValue().get("enabled"), false))
                    .map(Map.Entry::getKey)
                    .toList();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> job : loadJobs(agentId)) {
                Object dispatch = job.get("dispatch");
                if (!(dispatch instanceof Map<?, ?> dispatchMap)) continue;
                Object target = dispatchMap.get("target");
                if (!(target instanceof Map<?, ?> targetMap)) continue;
                String channel = stringValue(dispatchMap.get("channel"), "console");
                items.add(Map.of(
                        "channel", channel,
                        "user_id", stringValue(targetMap.get("user_id"), "default"),
                        "session_id", stringValue(targetMap.get("session_id"), "cron_job")
                ));
            }
            return ResponseEntity.ok(Map.of(
                    "channels", enabled,
                    "items", items,
                    "enabled", true
            ));
        });
    }

    private Mono<ResponseEntity<?>> setEnabled(String agentId, String id, boolean enabled) {
        return Mono.fromCallable(() -> {
            String effectiveAgent = AgentRequestSupport.agentId(agentId);
            boolean updated = schedulingService.setEnabled(
                    effectiveAgent, workspaceDir(effectiveAgent), id, enabled);
            return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        });
    }

    private Map<String, Object> stateFor(String agentId, Map<String, Object> job) {
        boolean enabled = booleanValue(job.getOrDefault("enabled", true), true);
        CronManager.CronJob runtime = schedulingService.runtimeJob(
                AgentRequestSupport.agentId(agentId), String.valueOf(job.get("id")));
        long lastRun = runtime != null && runtime.getLastRun() > 0
                ? runtime.getLastRun() : longValue(job.get("last_run_time"));
        long nextRun = runtime != null && runtime.getNextRun() > 0
                ? runtime.getNextRun() : longValue(job.get("next_run_time"));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("job_id", String.valueOf(job.get("id")));
        state.put("enabled", enabled);
        state.put("paused", !enabled);
        state.put("running", false);
        state.put("last_run_at", instantValue(lastRun, stringValue(job.get("last_run_at"), "")));
        state.put("next_run_at", instantValue(nextRun, stringValue(job.get("next_run_at"), "")));
        state.put("last_run_time", lastRun);
        state.put("next_run_time", nextRun);
        return state;
    }

    private String instantValue(long epochMillis, String fallback) {
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis).toString() : fallback;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void stampOwner(Map<String, Object> job, EnterprisePrincipal principal) {
        String owner = principal != null && principal.id() != null && !principal.id().isBlank()
                ? principal.id() : "default";
        job.put("owner_user_id", owner);
        normalizeDispatchOwner(job, owner);
    }

    private String ownerOf(Map<String, Object> job, EnterprisePrincipal fallback) {
        String owner = stringValue(job.get("owner_user_id"), "");
        if (!owner.isBlank()) return owner;
        return fallback != null && fallback.id() != null && !fallback.id().isBlank()
                ? fallback.id() : "default";
    }

    private void normalizeDispatchOwner(Map<String, Object> job, String owner) {
        Object raw = job.get("dispatch");
        Map<String, Object> dispatch = raw instanceof Map<?, ?> map ? mapCopy(map) : new LinkedHashMap<>();
        if (dispatch.isEmpty()) {
            dispatch.put("type", "channel");
            dispatch.put("channel", "console");
            dispatch.put("mode", "final");
        }
        Map<String, Object> target = mapCopy(dispatch.get("target"));
        if ("console".equalsIgnoreCase(stringValue(dispatch.get("channel"), "console"))) {
            target.put("user_id", owner);
        } else {
            target.putIfAbsent("user_id", owner);
        }
        target.putIfAbsent("session_id", "cron_job");
        dispatch.put("target", target);
        Map<String, Object> meta = mapCopy(dispatch.get("meta"));
        meta.put("execution_user_id", owner);
        dispatch.put("meta", meta);
        job.put("dispatch", dispatch);
    }

    private Map<String, Object> mapCopy(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void markRun(String agentId, String id, String runAt, String trigger, String status, String error) {
        CronJobStore.update(workspaceDir(agentId), id, job -> {
            job.put("last_run_at", runAt);
            job.put("last_run_time", Instant.parse(runAt).toEpochMilli());
            List<Map<String, Object>> history = new ArrayList<>();
            Object raw = job.get("history");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        history.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("run_at", runAt);
            record.put("status", status);
            record.put("trigger", trigger);
            if (error != null && !error.isBlank()) {
                record.put("error", error);
            }
            history.removeIf(item -> runAt.equals(String.valueOf(item.get("run_at"))));
            history.add(0, record);
            if (history.size() > 50) {
                history = new ArrayList<>(history.subList(0, 50));
            }
            job.put("history", history);
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyFor(Map<String, Object> job) {
        Object raw = job.get("history");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                history.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return history;
    }

    private List<Map<String, Object>> loadJobs(String agentId) {
        String effectiveAgent = AgentRequestSupport.agentId(agentId);
        List<Map<String, Object>> jobs = CronJobStore.load(workspaceDir(effectiveAgent));
        String boundOwner = identityStore.findAgentBinding(effectiveAgent)
                .map(EnterpriseIdentityStore.AgentBindingRecord::ownerUserId)
                .filter(value -> value != null && !value.isBlank())
                .orElse("");
        for (Map<String, Object> job : jobs) {
            job.put("agent_id", effectiveAgent);
            if (!boundOwner.isBlank()) {
                job.put("owner_user_id", boundOwner);
                normalizeDispatchOwner(job, boundOwner);
            }
        }
        return jobs;
    }

    private Path workspaceDir(String agentId) {
        return configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId));
    }

    private Map<String, Object> findJob(String agentId, String id) {
        return loadJobs(agentId).stream()
                .filter(job -> CronJobStore.matches(job, id))
                .findFirst()
                .orElse(null);
    }

    private void appendManualInboxEvent(String agentId, Map<String, Object> job, String runId, String text, Throwable err) {
        if (!booleanValue(job.getOrDefault("save_result_to_inbox", true), true)) {
            return;
        }
        boolean ok = err == null;
        inboxStore.appendTraceText(runId, "assistant", ok ? text : err.getMessage());
        inboxStore.finalizeTrace(runId, ok ? "success" : "error", ok ? null : err.getMessage());
        String jobId = String.valueOf(job.get("id"));
        String name = stringValue(job.get("name"), "Cron job");
        inboxStore.appendEvent(
                AgentRequestSupport.agentId(agentId),
                "cron",
                jobId,
                ok ? "cron_result" : "cron_error",
                ok ? "success" : "error",
                ok ? "info" : "error",
                (ok ? "Cron result: " : "Cron failed: ") + name,
                preview(ok ? text : err.getMessage(), ok ? "Agent cron task finished successfully." : "Cron task failed."),
                Map.of("job_id", jobId, "job_name", name, "task_type", stringValue(job.get("task_type"), "agent"), "trigger", "manual", "run_id", runId)
        );
    }

    private String preview(String value, String fallback) {
        String text = stringValue(value, fallback).trim();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String cleanText(com.melon.channels.ChannelOutboundMessage out) {
        if (out == null) return "";
        Object visible = out.getMeta().get("visible_text");
        String text = visible != null ? String.valueOf(visible) : "";
        return text.isBlank() ? out.getText() : text;
    }

}
