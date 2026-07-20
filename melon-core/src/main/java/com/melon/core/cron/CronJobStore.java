package com.melon.core.cron;

import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Workspace-local melonPaw-compatible jobs.json storage.
 */
public final class CronJobStore {

    private CronJobStore() {
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> load(Path workspaceDir) {
        Object list = JsonUtils.loadAsMap(jobsFile(workspaceDir)).get("jobs");
        if (list instanceof List<?>) {
            return new ArrayList<>((List<Map<String, Object>>) list);
        }
        return new ArrayList<>();
    }

    public static void save(Path workspaceDir, List<Map<String, Object>> jobs) {
        JsonUtils.saveOrThrow(jobsFile(workspaceDir), Map.of("version", 1, "jobs", jobs));
    }

    public static Map<String, Object> find(Path workspaceDir, String id) {
        return load(workspaceDir).stream().filter(job -> matches(job, id)).findFirst().orElse(null);
    }

    public static synchronized Map<String, Object> create(Path workspaceDir, Map<String, Object> body) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        Map<String, Object> job = normalize(body, UUID.randomUUID().toString());
        jobs.add(job);
        save(workspaceDir, jobs);
        return job;
    }

    public static synchronized Map<String, Object> replace(Path workspaceDir, String id, Map<String, Object> body) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        for (int i = 0; i < jobs.size(); i++) {
            if (matches(jobs.get(i), id)) {
                Map<String, Object> updated = normalize(body, id);
                updated.put("created_at", jobs.get(i).getOrDefault("created_at", Instant.now().toString()));
                jobs.set(i, updated);
                save(workspaceDir, jobs);
                return updated;
            }
        }
        return null;
    }

    public static synchronized boolean delete(Path workspaceDir, String id) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        boolean removed = jobs.removeIf(job -> matches(job, id));
        if (removed) {
            save(workspaceDir, jobs);
        }
        return removed;
    }

    public static synchronized boolean setEnabled(Path workspaceDir, String id, boolean enabled) {
        List<Map<String, Object>> jobs = load(workspaceDir);
        for (Map<String, Object> job : jobs) {
            if (matches(job, id)) {
                job.put("enabled", enabled);
                job.put("paused", !enabled);
                job.put("updated_at", Instant.now().toString());
                save(workspaceDir, jobs);
                return true;
            }
        }
        return false;
    }

    /** Atomically updates runtime metadata without overwriting concurrent job changes. */
    public static synchronized Map<String, Object> update(Path workspaceDir, String id,
                                                           Consumer<Map<String, Object>> updater) {
        return updateIf(workspaceDir, id, ignored -> true, updater);
    }

    /** Atomically updates a job only while its persisted version still matches the caller's view. */
    public static synchronized Map<String, Object> updateIf(Path workspaceDir, String id,
                                                             Predicate<Map<String, Object>> condition,
                                                             Consumer<Map<String, Object>> updater) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(updater, "updater");
        List<Map<String, Object>> jobs = load(workspaceDir);
        for (Map<String, Object> job : jobs) {
            if (matches(job, id)) {
                if (!condition.test(job)) return null;
                updater.accept(job);
                job.put("updated_at", Instant.now().toString());
                save(workspaceDir, jobs);
                return job;
            }
        }
        return null;
    }

    public static Map<String, Object> normalize(Map<String, Object> body, String id) {
        Map<String, Object> job = new LinkedHashMap<>();
        String name = stringValue(body.get("name"), "Cron job");
        String cron = cronValue(body);
        String prompt = promptValue(body);
        boolean enabled = booleanValue(body.getOrDefault("enabled", true), true);
        job.put("id", stringValue(body.get("id"), id));
        job.put("name", name);
        job.put("cron", cron);
        job.put("cron_expr", cron);
        job.put("prompt", prompt);
        job.put("query", prompt);
        job.put("enabled", enabled);
        job.put("paused", !enabled);
        Map<String, Object> schedule = scheduleValue(body, cron);
        job.put("schedule", schedule);
        if (schedule.get("timezone") != null) {
            job.put("timezone", String.valueOf(schedule.get("timezone")));
        }
        job.put("request", body.getOrDefault("request", request(prompt)));
        job.put("dispatch", body.getOrDefault("dispatch", Map.of("type", "channel", "channel", "console", "target", Map.of("user_id", "default"), "mode", "final", "meta", Map.of())));
        job.put("runtime", body.getOrDefault("runtime", Map.of("share_session", true, "max_concurrency", 1, "timeout_seconds", 180, "misfire_grace_seconds", 600)));
        job.put("task_type", stringValue(body.get("task_type"), "agent"));
        job.put("save_result_to_inbox", body.getOrDefault("save_result_to_inbox", true));
        job.put("meta", body.getOrDefault("meta", Map.of()));
        if (body.get("agent_id") != null) job.put("agent_id", String.valueOf(body.get("agent_id")));
        if (body.get("owner_user_id") != null) job.put("owner_user_id", String.valueOf(body.get("owner_user_id")));
        copyIfPresent(body, job, "history", "last_run_at", "last_run_time", "next_run_at", "next_run_time");
        job.put("created_at", stringValue(body.get("created_at"), Instant.now().toString()));
        job.put("updated_at", Instant.now().toString());
        return job;
    }

    public static boolean matches(Map<String, Object> job, String id) {
        return id.equals(String.valueOf(job.get("id"))) || id.equals(String.valueOf(job.get("name")));
    }

    public static Path jobsFile(Path workspaceDir) {
        return SafePathUtil.resolveSafe(workspaceDir, "jobs.json");
    }

    private static Object firstPresent(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return body.get(key);
            }
        }
        return null;
    }

    private static String cronValue(Map<String, Object> body) {
        Object schedule = body.get("schedule");
        if (schedule instanceof Map<?, ?> map && map.get("cron") != null) {
            return String.valueOf(map.get("cron"));
        }
        return stringValue(firstPresent(body, "cron", "cron_expr"), "");
    }

    private static Map<String, Object> scheduleValue(Map<String, Object> body, String cron) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        Object raw = body.get("schedule");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> schedule.put(String.valueOf(key), value));
        }
        String type = stringValue(schedule.get("type"), "cron");
        schedule.put("type", type);
        if ("cron".equalsIgnoreCase(type)) {
            schedule.put("cron", stringValue(schedule.get("cron"), cron));
            String timezone = stringValue(schedule.get("timezone"),
                    stringValue(body.get("timezone"), "Asia/Shanghai"));
            schedule.put("timezone", timezone);
        }
        return schedule;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target,
                                      String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) target.put(key, source.get(key));
        }
    }

    private static String promptValue(Map<String, Object> body) {
        String direct = stringValue(firstPresent(body, "prompt", "query", "message", "text"), "");
        if (!direct.isBlank()) return direct;
        Object request = body.get("request");
        if (!(request instanceof Map<?, ?> requestMap)) return "";
        Object input = requestMap.get("input");
        if (!(input instanceof List<?> messages)) return "";
        List<String> parts = new ArrayList<>();
        for (Object message : messages) {
            if (!(message instanceof Map<?, ?> messageMap)) continue;
            Object content = messageMap.get("content");
            if (!(content instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> blockMap && "text".equals(String.valueOf(blockMap.get("type")))) {
                    Object text = blockMap.get("text");
                    parts.add(text != null ? String.valueOf(text) : "");
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private static Map<String, Object> request(String prompt) {
        return Map.of("input", List.of(Map.of(
                "role", "user",
                "type", "message",
                "content", List.of(Map.of("type", "text", "text", prompt))
        )));
    }
}
