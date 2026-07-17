package com.melon.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class InboxStore {

    private static final int MAX_EVENTS = 5000;
    private static final TypeReference<List<Map<String, Object>>> EVENT_LIST = new TypeReference<>() {};
    private final ConfigManager configManager;

    public InboxStore(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public synchronized Map<String, Object> appendEvent(String agentId, String sourceType, String sourceId,
                                                        String eventType, String status, String severity,
                                                        String title, String body, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("agent_id", blank(agentId) ? "default" : agentId);
        event.put("source_type", value(sourceType));
        event.put("source_id", value(sourceId));
        event.put("event_type", value(eventType));
        event.put("status", value(status));
        event.put("severity", blank(severity) ? "info" : severity);
        event.put("title", value(title));
        event.put("body", value(body));
        event.put("payload", payload != null ? payload : Map.of());
        event.put("read", false);
        event.put("created_at", Instant.now().toEpochMilli() / 1000.0);
        List<Map<String, Object>> events = events();
        events.add(0, event);
        if (events.size() > MAX_EVENTS) {
            events = new ArrayList<>(events.subList(0, MAX_EVENTS));
        }
        JsonUtils.save(eventsFile(), events);
        return event;
    }

    public synchronized List<Map<String, Object>> listEvents(int limit, int offset, String sourceType,
                                                             String status, String agentId, boolean unreadOnly) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> event : events()) {
            if (!blank(sourceType) && !sourceType.equals(String.valueOf(event.get("source_type")))) continue;
            if (!blank(status) && !status.equals(String.valueOf(event.get("status")))) continue;
            if (!blank(agentId) && !agentId.equals(String.valueOf(event.get("agent_id")))) continue;
            if (unreadOnly && Boolean.TRUE.equals(event.get("read"))) continue;
            filtered.add(event);
        }
        int from = Math.min(Math.max(offset, 0), filtered.size());
        int to = Math.min(from + Math.max(limit, 0), filtered.size());
        return filtered.subList(from, to);
    }

    public synchronized int markRead(List<String> ids) {
        return markRead(ids, null);
    }

    public synchronized int markRead(List<String> ids, String agentId) {
        if (ids == null || ids.isEmpty()) return 0;
        int updated = 0;
        List<Map<String, Object>> events = events();
        for (Map<String, Object> event : events) {
            if (matchesAgent(event, agentId)
                    && ids.contains(String.valueOf(event.get("id")))
                    && !Boolean.TRUE.equals(event.get("read"))) {
                event.put("read", true);
                updated++;
            }
        }
        if (updated > 0) JsonUtils.save(eventsFile(), events);
        return updated;
    }

    public synchronized int markAllRead() {
        return markAllRead(null);
    }

    public synchronized int markAllRead(String agentId) {
        int updated = 0;
        List<Map<String, Object>> events = events();
        for (Map<String, Object> event : events) {
            if (matchesAgent(event, agentId) && !Boolean.TRUE.equals(event.get("read"))) {
                event.put("read", true);
                updated++;
            }
        }
        if (updated > 0) JsonUtils.save(eventsFile(), events);
        return updated;
    }

    public synchronized Map<String, Object> deleteEvent(String eventId) {
        return deleteEvent(eventId, null);
    }

    public synchronized Map<String, Object> deleteEvent(String eventId, String agentId) {
        List<Map<String, Object>> events = events();
        String traceId = null;
        boolean deleted = false;
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if (!deleted && matchesAgent(event, agentId)
                    && eventId.equals(String.valueOf(event.get("id")))) {
                traceId = traceId(event);
                deleted = true;
                continue;
            }
            kept.add(event);
        }
        if (!deleted) return Map.of("deleted", false);
        JsonUtils.save(eventsFile(), kept);
        String deletedTraceId = traceId;
        boolean stillReferenced = deletedTraceId != null
                && kept.stream().anyMatch(event -> deletedTraceId.equals(traceId(event)));
        boolean traceDeleted = false;
        if (deletedTraceId != null && !stillReferenced) {
            try {
                traceDeleted = Files.deleteIfExists(traceFile(deletedTraceId));
            } catch (Exception ignored) {
                traceDeleted = false;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("trace_deleted", traceDeleted);
        result.put("trace_id", deletedTraceId);
        result.put("run_id", deletedTraceId); // Legacy console clients used run_id for the trace id.
        return result;
    }

    public synchronized void createTrace(String runId, Map<String, Object> meta) {
        if (blank(runId)) return;
        if (Files.isRegularFile(traceFile(runId))) {
            mergeTraceMeta(runId, meta);
            return;
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("trace_id", runId);
        trace.put("run_id", runId);
        trace.put("created_at", Instant.now().toEpochMilli() / 1000.0);
        trace.put("completed_at", null);
        trace.put("status", "running");
        trace.put("meta", meta != null ? meta : Map.of());
        trace.put("events", new ArrayList<>());
        JsonUtils.save(traceFile(runId), trace);
    }

    public synchronized void linkTask(String traceId, String taskId, String runId) {
        if (blank(traceId) || blank(taskId) || blank(runId)) return;
        if (!Files.isRegularFile(traceFile(traceId))) return;
        mergeTraceMeta(traceId, Map.of("task_id", taskId, "run_id", runId));
    }

    public synchronized Map<String, Object> linkedPayload(String traceId, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>(payload != null ? payload : Map.of());
        result.put("trace_id", traceId);
        Object metaRaw = getTrace(traceId).get("meta");
        if (metaRaw instanceof Map<?, ?> meta) {
            if (meta.get("task_id") != null) result.put("task_id", meta.get("task_id"));
            if (meta.get("run_id") != null) result.put("run_id", meta.get("run_id"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public synchronized void appendTraceText(String runId, String role, String text) {
        if (blank(runId) || blank(text)) return;
        Map<String, Object> trace = getTrace(runId);
        List<Map<String, Object>> events = trace.get("events") instanceof List<?> raw
                ? new ArrayList<>((List<Map<String, Object>>) raw)
                : new ArrayList<>();
        events.add(Map.of(
                "at", Instant.now().toEpochMilli() / 1000.0,
                "event", Map.of(
                        "role", blank(role) ? "assistant" : role,
                        "name", blank(role) ? "assistant" : role,
                        "content", List.of(Map.of("type", "text", "text", text))
                )
        ));
        trace.put("events", events);
        JsonUtils.save(traceFile(runId), trace);
    }

    public synchronized void finalizeTrace(String runId, String status, String error) {
        if (blank(runId)) return;
        Map<String, Object> trace = getTrace(runId);
        trace.put("completed_at", Instant.now().toEpochMilli() / 1000.0);
        trace.put("status", blank(status) ? "success" : status);
        if (!blank(error)) trace.put("error", error);
        JsonUtils.save(traceFile(runId), trace);
    }

    public synchronized Map<String, Object> getTrace(String runId) {
        Map<String, Object> trace = JsonUtils.loadAsMap(traceFile(runId));
        if (!trace.isEmpty()) return trace;
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("trace_id", runId);
        missing.put("run_id", runId);
        missing.put("created_at", Instant.now().toEpochMilli() / 1000.0);
        missing.put("completed_at", null);
        missing.put("status", "not_found");
        missing.put("meta", Map.of());
        missing.put("events", List.of());
        return missing;
    }

    public synchronized Map<String, Object> getTrace(String runId, String agentId) {
        boolean accessible = events().stream().anyMatch(event ->
                matchesAgent(event, agentId) && runId.equals(traceId(event)));
        return accessible ? getTrace(runId) : missingTrace(runId);
    }

    @SuppressWarnings("unchecked")
    private void mergeTraceMeta(String traceId, Map<String, Object> additions) {
        if (additions == null || additions.isEmpty()) return;
        Map<String, Object> trace = getTrace(traceId);
        Map<String, Object> meta = trace.get("meta") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw) : new LinkedHashMap<>();
        meta.putAll(additions);
        trace.put("meta", meta);
        JsonUtils.save(traceFile(traceId), trace);
    }

    private List<Map<String, Object>> events() {
        List<Map<String, Object>> events = JsonUtils.load(eventsFile(), EVENT_LIST);
        return events != null ? new ArrayList<>(events) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private String traceId(Map<String, Object> event) {
        Object payload = event.get("payload");
        if (payload instanceof Map<?, ?> map) {
            if (map.get("trace_id") != null) return String.valueOf(map.get("trace_id"));
            if (map.get("run_id") != null) return String.valueOf(map.get("run_id"));
        }
        return null;
    }

    private Path eventsFile() {
        return configManager.resolveHomeDir().resolve("inbox_events.json");
    }

    private Path traceFile(String runId) {
        String safeRunId = runId == null ? "" : runId.trim();
        if (!safeRunId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Invalid inbox trace id");
        }
        return configManager.resolveHomeDir().resolve("inbox_traces").resolve(safeRunId + ".json");
    }

    private boolean matchesAgent(Map<String, Object> event, String agentId) {
        return blank(agentId) || agentId.equals(String.valueOf(event.get("agent_id")));
    }

    private Map<String, Object> missingTrace(String runId) {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("trace_id", runId);
        missing.put("run_id", runId);
        missing.put("created_at", Instant.now().toEpochMilli() / 1000.0);
        missing.put("completed_at", null);
        missing.put("status", "not_found");
        missing.put("meta", Map.of());
        missing.put("events", List.of());
        return missing;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
