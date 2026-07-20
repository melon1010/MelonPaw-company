package com.melon.app.runner;

import io.agentscope.core.message.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

/** Shared visibility contract for model context, persisted history, and frontend projection. */
public final class ConversationVisibility {

    public static final String VISIBILITY_KEY = "melonpaw.visibility";
    public static final String SYNTHETIC_REASON_KEY = "melonpaw.synthetic_reason";
    public static final String USER_VISIBLE = "user_visible";
    public static final String MODEL_ONLY = "model_only";

    private static final List<String> INTERNAL_ENVELOPES = List.of(
            "<system-reminder", "<system-notification", "<monitor-event",
            "<task-completion", "<subagent-completion", "<classifier-evidence");
    private static final String COMPACTION_SUMMARY_PREFIX =
            "You are in the middle of a conversation that has been summarized.";

    private ConversationVisibility() {}

    public static UserMessage modelOnlyUserMessage(String text, String reason) {
        return UserMessage.builder()
                .name("__internal__")
                .textContent(text)
                .metadata(modelOnlyMetadata(Map.of(), reason))
                .build();
    }

    public static Map<String, Object> modelOnlyMetadata(
            Map<String, Object> existing, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>(existing != null ? existing : Map.of());
        metadata.put(VISIBILITY_KEY, MODEL_ONLY);
        metadata.put(SYNTHETIC_REASON_KEY, reason == null || reason.isBlank() ? "internal" : reason);
        return Map.copyOf(metadata);
    }

    public static boolean isModelOnly(Map<String, Object> message) {
        if (message == null || message.isEmpty()) return false;
        String role = lower(message.get("role"));
        String name = lower(message.get("name"));
        if ("system".equals(role) || isInternalName(name)) return true;

        Map<?, ?> metadata = message.get("metadata") instanceof Map<?, ?> map ? map : Map.of();
        String visibility = metadataValue(metadata, VISIBILITY_KEY, "visibility");
        if (MODEL_ONLY.equalsIgnoreCase(visibility)
                || "internal".equalsIgnoreCase(visibility)
                || "hidden".equalsIgnoreCase(visibility)) {
            return true;
        }
        if (booleanValue(metadata.get("hideFromScrollback"))
                || booleanValue(metadata.get("hide_from_scrollback"))) {
            return true;
        }
        if (!metadataValue(metadata, SYNTHETIC_REASON_KEY, "synthetic_reason").isBlank()) {
            return true;
        }

        String text = firstText(message.get("content")).stripLeading();
        if (text.startsWith(COMPACTION_SUMMARY_PREFIX)) return true;
        if (("user".equals(role) || "system".equals(name)) && startsWithInternalEnvelope(text)) {
            return true;
        }
        return "system".equals(name) && !"assistant".equals(role);
    }

    public static boolean isInternalName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "__compaction_summary__".equals(normalized)
                || "__system__".equals(normalized)
                || "__internal__".equals(normalized);
    }

    public static boolean startsWithInternalEnvelope(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.stripLeading().toLowerCase(Locale.ROOT);
        return INTERNAL_ENVELOPES.stream().anyMatch(normalized::startsWith);
    }

    public static String firstText(Object content) {
        if (content instanceof String text) return text;
        if (!(content instanceof List<?> blocks)) return "";
        for (Object item : blocks) {
            if (!(item instanceof Map<?, ?> block)) continue;
            String text = stringValue(block.get("text"));
            if (text.isBlank()) text = stringValue(block.get("thinking"));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static String metadataValue(Map<?, ?> metadata, String primary, String legacy) {
        String value = stringValue(metadata.get(primary));
        if (value.isBlank()) value = stringValue(metadata.get(legacy));
        Object nested = metadata.get("metadata");
        if (value.isBlank() && nested instanceof Map<?, ?> map) {
            value = stringValue(map.get(primary));
            if (value.isBlank()) value = stringValue(map.get(legacy));
        }
        return value;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(stringValue(value));
    }

    private static String lower(Object value) {
        return stringValue(value).toLowerCase(Locale.ROOT);
    }
}
