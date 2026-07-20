package com.melon.app.cron;

import com.melon.core.cron.CronJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

/** Owns validation, persistence coordination, and live registration for workspace cron jobs. */
@Service
public class CronSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(CronSchedulingService.class);
    private final CronManager cronManager;

    public CronSchedulingService(CronManager cronManager) {
        this.cronManager = cronManager;
    }

    public Map<String, Object> create(String agentId, Path workspaceDir, Map<String, Object> body) {
        Map<String, Object> candidate = CronJobStore.normalize(
                body != null ? body : Map.of(), UUID.randomUUID().toString());
        candidate.put("agent_id", normalizeAgentId(agentId));
        validate(agentId, candidate);

        Map<String, Object> stored = CronJobStore.create(workspaceDir, candidate);
        try {
            sync(agentId, workspaceDir, stored, true);
            return stored;
        } catch (RuntimeException e) {
            CronJobStore.delete(workspaceDir, String.valueOf(stored.get("id")));
            throw e;
        }
    }

    public Map<String, Object> replace(String agentId, Path workspaceDir, String id,
                                       Map<String, Object> body) {
        Map<String, Object> previous = CronJobStore.find(workspaceDir, id);
        if (previous == null) return null;

        Map<String, Object> replacement = new LinkedHashMap<>(body != null ? body : Map.of());
        replacement.put("id", id);
        replacement.put("agent_id", normalizeAgentId(agentId));
        replacement.putIfAbsent("owner_user_id", previous.get("owner_user_id"));
        replacement.putIfAbsent("created_at", previous.get("created_at"));
        preserveRuntimeMetadata(previous, replacement);
        Map<String, Object> candidate = CronJobStore.normalize(replacement, id);
        validate(agentId, candidate);

        Map<String, Object> updated = CronJobStore.replace(workspaceDir, id, candidate);
        try {
            sync(agentId, workspaceDir, updated, true);
            return updated;
        } catch (RuntimeException e) {
            CronJobStore.replace(workspaceDir, id, previous);
            try {
                sync(agentId, workspaceDir, previous, true);
            } catch (RuntimeException ignored) {
                // Preserve the original scheduling error; the persisted definition was restored.
            }
            throw e;
        }
    }

    public boolean delete(String agentId, Path workspaceDir, String id) {
        boolean deleted = CronJobStore.delete(workspaceDir, id);
        if (deleted) cancel(agentId, id);
        return deleted;
    }

    public boolean setEnabled(String agentId, Path workspaceDir, String id, boolean enabled) {
        Map<String, Object> previous = CronJobStore.find(workspaceDir, id);
        if (previous == null) return false;
        boolean wasEnabled = booleanValue(previous.getOrDefault("enabled", true), true);
        if (!CronJobStore.setEnabled(workspaceDir, id, enabled)) return false;
        Map<String, Object> job = CronJobStore.find(workspaceDir, id);
        try {
            if (enabled && job != null) {
                sync(agentId, workspaceDir, job, true);
            } else {
                cancel(agentId, id);
            }
        } catch (RuntimeException e) {
            CronJobStore.setEnabled(workspaceDir, id, wasEnabled);
            if (wasEnabled) {
                try {
                    sync(agentId, workspaceDir, previous, true);
                } catch (RuntimeException ignored) {
                    // Preserve the validation or scheduling failure that caused the rollback.
                }
            }
            throw e;
        }
        return true;
    }

    public void syncAll(String agentId, Path workspaceDir, List<Map<String, Object>> jobs) {
        if (jobs == null) return;
        for (Map<String, Object> job : jobs) {
            try {
                sync(agentId, workspaceDir, job, false);
            } catch (RuntimeException e) {
                log.warn("Skipping invalid persisted cron job: agent={}, id={}, reason={}",
                        normalizeAgentId(agentId), stringValue(job.get("id"), ""), e.getMessage());
            }
        }
    }

    public void sync(String agentId, Path workspaceDir, Map<String, Object> job, boolean force) {
        if (job == null) throw new IllegalArgumentException("Cron job is required");
        String sourceId = stringValue(job.get("id"), "");
        if (sourceId.isBlank()) throw new IllegalArgumentException("Cron job id is required");
        String runtimeId = runtimeId(agentId, sourceId);
        if (!booleanValue(job.getOrDefault("enabled", true), true)) {
            cronManager.cancel(runtimeId);
            return;
        }
        CronManager.CronJob runtimeJob = toRuntimeJob(agentId, job);
        validate(runtimeJob);
        configureCompletionCallback(workspaceDir, job, runtimeJob);
        if (!force && cronManager.get(runtimeId) != null) return;

        cronManager.cancel(runtimeId);
        cronManager.schedule(runtimeJob);
    }

    public void validate(String agentId, Map<String, Object> job) {
        validate(toRuntimeJob(agentId, job));
    }

    public void cancel(String agentId, String jobId) {
        cronManager.cancel(runtimeId(agentId, jobId));
    }

    public CronManager.CronJob runtimeJob(String agentId, String jobId) {
        return cronManager.get(runtimeId(agentId, jobId));
    }

    public String promptValue(Map<String, Object> job) {
        String direct = stringValue(firstPresent(job, "prompt", "query", "text", "message"), "");
        if (!direct.isBlank()) return direct;
        Object request = job.get("request");
        if (!(request instanceof Map<?, ?> requestMap)) return "";
        Object input = requestMap.get("input");
        if (!(input instanceof List<?> messages)) return "";
        List<String> parts = new ArrayList<>();
        for (Object message : messages) {
            if (!(message instanceof Map<?, ?> messageMap)) continue;
            Object content = messageMap.get("content");
            if (!(content instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> blockMap
                        && "text".equals(String.valueOf(blockMap.get("type")))) {
                    parts.add(stringValue(blockMap.get("text"), ""));
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    public Map<String, Object> dispatch(Map<String, Object> job) {
        Object raw = job.get("dispatch");
        Map<String, Object> copy = raw instanceof Map<?, ?> map ? mapCopy(map) : new LinkedHashMap<>();
        String owner = ownerOf(job);
        copy.putIfAbsent("type", "channel");
        copy.putIfAbsent("channel", "console");
        copy.putIfAbsent("mode", "final");
        Map<String, Object> target = mapCopy(copy.get("target"));
        target.putIfAbsent("user_id", owner);
        target.putIfAbsent("session_id", "cron_job");
        copy.put("target", target);
        Map<String, Object> meta = mapCopy(copy.get("meta"));
        meta.put("execution_user_id", owner);
        copy.put("meta", meta);
        return copy;
    }

    CronManager.CronJob toRuntimeJob(String agentId, Map<String, Object> job) {
        if (job == null) throw new IllegalArgumentException("Cron job is required");
        CronManager.CronJob cronJob = new CronManager.CronJob();
        String sourceId = stringValue(job.get("id"), "");
        if (sourceId.isBlank()) throw new IllegalArgumentException("Cron job id is required");
        cronJob.setId(runtimeId(agentId, sourceId));
        cronJob.setSourceId(sourceId);
        cronJob.setName(stringValue(job.get("name"), "Cron job"));
        cronJob.setAgentId(normalizeAgentId(agentId));
        cronJob.setMessage(promptValue(job));
        cronJob.setEnabled(booleanValue(job.getOrDefault("enabled", true), true));
        cronJob.setSaveResultToInbox(booleanValue(job.getOrDefault("save_result_to_inbox", true), true));
        cronJob.setTaskType(stringValue(job.get("task_type"), "agent"));
        cronJob.setDispatch(dispatch(job));

        Object schedule = job.get("schedule");
        if (schedule instanceof Map<?, ?> map && "once".equalsIgnoreCase(String.valueOf(map.get("type")))) {
            cronJob.setTriggerType(CronManager.TriggerType.ONE_SHOT);
            cronJob.setTimezone(timezoneValue(job));
            cronJob.setDelayMs(delayUntil(stringValue(map.get("run_at"), ""), cronJob.getTimezone()));
        } else {
            cronJob.setTriggerType(CronManager.TriggerType.CRON);
            cronJob.setCronExpression(normalizeCronExpression(
                    stringValue(job.get("cron"), stringValue(job.get("cron_expr"), ""))));
            cronJob.setTimezone(timezoneValue(job));
        }
        return cronJob;
    }

    private void validate(CronManager.CronJob job) {
        if (job.getMessage() == null || job.getMessage().isBlank()) {
            throw new IllegalArgumentException("Cron job prompt is required");
        }
        if (job.getTriggerType() == CronManager.TriggerType.CRON) {
            if (job.getCronExpression() == null || job.getCronExpression().isBlank()) {
                throw new IllegalArgumentException("Cron expression is required");
            }
            ZoneId zone = zoneId(job.getTimezone());
            CronExpression expression = CronExpression.parse(job.getCronExpression());
            if (expression.next(ZonedDateTime.now(zone)) == null) {
                throw new IllegalArgumentException("Cron expression has no future execution time");
            }
        }
    }

    private String timezoneValue(Map<String, Object> job) {
        Object schedule = job.get("schedule");
        String timezone = schedule instanceof Map<?, ?> map
                ? stringValue(map.get("timezone"), "") : "";
        if (timezone.isBlank()) timezone = stringValue(job.get("timezone"), "Asia/Shanghai");
        return zoneId(timezone).getId();
    }

    private ZoneId zoneId(String timezone) {
        try {
            String value = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim();
            return ZoneId.of(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid IANA timezone: " + timezone, e);
        }
    }

    private String normalizeCronExpression(String cron) {
        String value = cron == null ? "" : cron.trim().replaceAll("\\s+", " ");
        return value.split(" ").length == 5 ? "0 " + value : value;
    }

    private long delayUntil(String runAt, String timezone) {
        if (runAt == null || runAt.isBlank()) throw new IllegalArgumentException("run_at is required");
        long runAtMillis;
        try {
            runAtMillis = Instant.parse(runAt).toEpochMilli();
        } catch (DateTimeParseException first) {
            try {
                runAtMillis = ZonedDateTime.parse(runAt).toInstant().toEpochMilli();
            } catch (DateTimeParseException second) {
                try {
                    runAtMillis = LocalDateTime.parse(runAt).atZone(zoneId(timezone))
                            .toInstant().toEpochMilli();
                } catch (DateTimeParseException third) {
                    throw new IllegalArgumentException("Invalid run_at timestamp: " + runAt, third);
                }
            }
        }
        long delay = runAtMillis - System.currentTimeMillis();
        if (delay <= 0) throw new IllegalArgumentException("run_at must be in the future");
        return delay;
    }

    private String runtimeId(String agentId, String jobId) {
        return normalizeAgentId(agentId) + ":" + jobId;
    }

    private void configureCompletionCallback(Path workspaceDir, Map<String, Object> storedJob,
                                             CronManager.CronJob runtimeJob) {
        if (runtimeJob.getTriggerType() != CronManager.TriggerType.ONE_SHOT) return;
        String sourceId = runtimeJob.getSourceId();
        String scheduledVersion = stringValue(storedJob.get("updated_at"), "");
        runtimeJob.setCompletionCallback(() -> CronJobStore.updateIf(
                workspaceDir,
                sourceId,
                current -> scheduledVersion.equals(stringValue(current.get("updated_at"), "")),
                current -> {
                    current.put("enabled", false);
                    current.put("paused", true);
                    current.put("completed_at", Instant.now().toString());
                }));
    }

    private String normalizeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId.trim();
    }

    private String ownerOf(Map<String, Object> job) {
        String owner = stringValue(job.get("owner_user_id"), "");
        if (!owner.isBlank()) return owner;
        Object rawDispatch = job.get("dispatch");
        if (rawDispatch instanceof Map<?, ?> dispatch) {
            Object rawTarget = dispatch.get("target");
            if (rawTarget instanceof Map<?, ?> target) {
                owner = stringValue(target.get("user_id"), "");
            }
        }
        return owner.isBlank() ? "default" : owner;
    }

    private void preserveRuntimeMetadata(Map<String, Object> source, Map<String, Object> target) {
        for (String key : List.of("history", "last_run_at", "last_run_time",
                "next_run_at", "next_run_time")) {
            if (!target.containsKey(key) && source.containsKey(key)) target.put(key, source.get(key));
        }
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private Map<String, Object> mapCopy(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }
}
