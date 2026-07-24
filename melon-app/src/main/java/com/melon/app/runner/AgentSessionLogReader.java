package com.melon.app.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Reads AgentScope session logs and returns the message shape the copied
 * melonPaw frontend already understands.
 */
@Component
public class AgentSessionLogReader {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionLogReader.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ConfigManager configManager;
    private final ChatManager chatManager;
    private final AgentStateLookup stateLookup;

    public AgentSessionLogReader(ConfigManager configManager, ChatManager chatManager) {
        this(configManager, chatManager,
                new JsonFileAgentStateStore(configManager.resolveStateDir()));
    }

    @Autowired
    public AgentSessionLogReader(ConfigManager configManager, ChatManager chatManager,
                                 AgentStateStore stateStore) {
        this.configManager = configManager;
        this.chatManager = chatManager;
        this.stateLookup = new AgentStateLookup(stateStore);
    }

    public List<Map<String, Object>> readFrontendMessages(String agentId, String sessionId) {
        return readFrontendMessages(agentId, "default", "console", sessionId);
    }

    public List<Map<String, Object>> readFrontendMessages(String agentId, String userId, String channel, String sessionId) {
        Map<String, Object> shadow = chatManager.loadSessionShadow(agentId, channel, userId, sessionId);
        List<Map<String, Object>> shadowMessages = deduplicateMessages(readPythonSessionMessages(shadow));
        if (!shadowMessages.isEmpty() && !looksTruncatedConsoleHistory(shadowMessages)) {
            return annotateTurnPhases(shadowMessages);
        }

        String stateUserId = chatManager.stateUserId(agentId, channel, sessionId, userId);
        Map<String, Object> state = stateLookup.findAsMap(agentId, stateUserId, sessionId);
        if (!state.isEmpty()) {
            List<Map<String, Object>> messages = deduplicateMessages(readStateMessages(state));
            if (!messages.isEmpty() && !looksTruncatedConsoleHistory(messages)) {
                return annotateTurnPhases(messages);
            }
        }

        Path logFile = findSessionLog(agentId, sessionId);
        if (logFile == null) return List.of();

        List<Map<String, Object>> messages = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile)) {
                if (line.isBlank()) continue;
                Map<String, Object> raw = JsonUtils.getMapper().readValue(line, MAP_TYPE);
                messages.addAll(convert(raw));
            }
        } catch (IOException e) {
            log.warn("Failed to read AgentScope session log: {}", logFile, e);
            return List.of();
        }
        return annotateTurnPhases(deduplicateMessages(messages));
    }

    private List<Map<String, Object>> annotateTurnPhases(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        int start = 0;
        while (start < messages.size()) {
            int end = start + 1;
            while (end < messages.size() && !"user".equals(lower(messages.get(end).get("role")))) end++;
            String turnId = "user".equals(lower(messages.get(start).get("role")))
                    ? stringValue(messages.get(start).get("id")) : "turn-" + start;
            if (turnId.isBlank()) turnId = "turn-" + start;

            int finalText = -1;
            for (int i = start; i < end; i++) {
                Map<String, Object> message = messages.get(i);
                if ("assistant".equals(lower(message.get("role")))
                        && "message".equals(lower(message.get("type")))) finalText = i;
            }
            for (int i = start; i < end; i++) {
                Map<String, Object> copy = new LinkedHashMap<>(messages.get(i));
                Map<String, Object> metadata = copy.get("metadata") instanceof Map<?, ?> raw
                        ? asMap(raw) : new LinkedHashMap<>();
                String role = lower(copy.get("role"));
                String type = lower(copy.get("type"));
                metadata.put("turn_id", turnId);
                if ("user".equals(role)) {
                    metadata.put("phase", "input");
                } else if ("assistant".equals(role) && "message".equals(type)) {
                    metadata.put("phase", i == finalText ? "final" : "progress");
                    metadata.put("terminal", i == finalText);
                } else if ("reasoning".equals(type)) {
                    metadata.put("phase", "reasoning");
                } else {
                    metadata.put("phase", "tool");
                }
                copy.put("metadata", metadata);
                result.add(copy);
            }
            start = end;
        }
        return result;
    }

    private List<Map<String, Object>> deduplicateMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> currentTurn = new ArrayList<>();
        java.util.Set<String> seenMessageIds = new java.util.HashSet<>();
        for (Map<String, Object> message : messages) {
            if (isInternalFrontendMessage(message)) continue;
            String messageId = stringValue(message.get("id"));
            if (!messageId.isBlank() && !seenMessageIds.add(messageId)) continue;
            if ("user".equals(lower(message.get("role")))) {
                currentTurn.clear();
                result.add(message);
                continue;
            }
            List<String> fingerprints = frontendFingerprints(message);
            if (!fingerprints.isEmpty() && currentTurn.containsAll(fingerprints)) continue;
            currentTurn.addAll(fingerprints);
            result.add(message);
        }
        return result;
    }

    private List<String> frontendFingerprints(Map<String, Object> message) {
        Object content = message.get("content");
        if (!(content instanceof List<?> blocks)) return List.of();
        String messageType = stringValue(message.get("type"));
        List<String> fingerprints = new ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> raw)) continue;
            String type = stringValue(raw.get("type"));
            if ("data".equals(type) && raw.get("data") instanceof Map<?, ?> data) {
                String callId = stringValue(data.get("call_id"));
                fingerprints.add(!callId.isBlank()
                        ? messageType + ":" + callId
                        : messageType + ":" + JsonUtils.toJson(data));
            } else if ("text".equals(type)) {
                String text = stringValue(raw.get("text"));
                if (!text.isBlank()) fingerprints.add(messageType + ":" + text);
            } else {
                fingerprints.add(messageType + ":" + JsonUtils.toJson(raw));
            }
        }
        return fingerprints;
    }

    private boolean looksTruncatedConsoleHistory(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) return false;
        if (isInternalFrontendMessage(messages.get(0))) return true;
        String firstRole = lower(messages.get(0).get("role"));
        return !"user".equals(firstRole);
    }

    private List<Map<String, Object>> readPythonSessionMessages(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Object agentRaw = raw.get("agent");
        if (!(agentRaw instanceof Map<?, ?> agentMap)) return List.of();
        Object stateRaw = asMap(agentMap).get("state");
        if (!(stateRaw instanceof Map<?, ?> stateMap)) return List.of();
        Object context = asMap(stateMap).get("context");
        if (!(context instanceof List<?> items)) return List.of();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> message) {
                messages.addAll(convertStateMessage(asMap(message)));
            }
        }
        return messages;
    }

    private List<Map<String, Object>> readStateMessages(Map<String, Object> state) {
        Object context = state.get("context");
        if (!(context instanceof List<?> items)) return List.of();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> raw) {
                messages.addAll(convertStateMessage(asMap(raw)));
            }
        }
        return messages;
    }

    private List<Map<String, Object>> convertStateMessage(Map<String, Object> raw) {
        if (isInternalStateMessage(raw)) return List.of();
        String role = lower(raw.get("role"));
        if (role.isBlank()) role = "assistant";
        Object content = raw.get("content");
        Map<String, Object> metadata = stateMetadata(raw);

        if (content instanceof String text) {
            Map<String, Object> message = runtimeMessage(rawId(raw), "message", role, metadata);
            message.put("content", List.of(textContent(stripInjectedSkillBlock(text, role))));
            return List.of(message);
        }
        if (!(content instanceof List<?> blocks)) return List.of();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> current = null;
        String currentType = null;
        int splitIndex = 0;
        java.util.Set<String> seenBlocks = new java.util.HashSet<>();
        for (Object item : blocks) {
            if (!(item instanceof Map<?, ?> blockRaw)) continue;
            Map<String, Object> block = asMap(blockRaw);
            String blockType = normalizedBlockType(block);
            String toolName = stringValue(block.get("name"));
            if (("tool_use".equals(blockType) || "tool_call".equals(blockType)
                    || "tool_result".equals(blockType))
                    && FrontendToolCompat.isInternalTool(toolName)) {
                current = null;
                currentType = null;
                continue;
            }
            if ("tool_use".equals(blockType) || "tool_call".equals(blockType)
                    || "tool_result".equals(blockType)) {
                String blockId = stringValue(block.get("id"));
                String blockKey = blockType + ":" + blockId;
                if (!blockId.isBlank() && !seenBlocks.add(blockKey)) continue;
            }
            if ("text".equals(blockType) || isMediaBlock(blockType)) {
                if (!"message".equals(currentType)) {
                    current = startStateMessage(raw, "message", role, metadata, splitIndex++);
                    currentType = "message";
                    messages.add(current);
                }
                contentList(current).add(contentFromBlock(block, blockType, role));
            } else if ("thinking".equals(blockType)) {
                if (!"reasoning".equals(currentType)) {
                    current = startStateMessage(raw, "reasoning", role, metadata, splitIndex++);
                    currentType = "reasoning";
                    messages.add(current);
                }
                contentList(current).add(textContent(stringValue(block.get("thinking"))));
            } else if ("tool_use".equals(blockType) || "tool_call".equals(blockType)) {
                current = null;
                currentType = null;
                messages.add(toolMessage(rawId(raw) + "_tool_" + splitIndex++, role,
                        "plugin_call", stringValue(block.get("name")), stringValue(block.get("id")),
                        toolArgumentsJson(stringValue(block.get("name")), block.get("input")), null, metadata));
            } else if ("tool_result".equals(blockType)) {
                current = null;
                currentType = null;
                messages.add(toolMessage(rawId(raw) + "_tool_result_" + splitIndex++, role,
                        "plugin_call_output", stringValue(block.get("name")), stringValue(block.get("id")),
                        null, jsonStringOrRaw(block.get("output")), metadata));
            } else {
                if (!"message".equals(currentType)) {
                    current = startStateMessage(raw, "message", role, metadata, splitIndex++);
                    currentType = "message";
                    messages.add(current);
                }
                contentList(current).add(textContent(String.valueOf(block)));
            }
        }
        return messages;
    }

    private Path findSessionLog(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Path workspace = configManager.resolveWorkspaceDir(agentId);
        List<Path> candidates = List.of(
                workspace.resolve("agents"),
                workspace.resolve("default").resolve("agents")
        );
        for (Path agentsDir : candidates) {
            Path found = findUnderAgents(agentsDir, sessionId + ".log.jsonl");
            if (found != null) return found;
            found = findUnderAgents(agentsDir, sessionId + ".jsonl");
            if (found != null) return found;
        }
        return null;
    }

    private Path findUnderAgents(Path agentsDir, String fileName) {
        if (!Files.isDirectory(agentsDir)) return null;
        try (var stream = Files.find(agentsDir, 4,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equals(fileName))) {
            return stream.sorted(Comparator.comparing(Path::toString)).findFirst().orElse(null);
        } catch (IOException e) {
            log.debug("Failed to scan session logs under {}", agentsDir, e);
            return null;
        }
    }

    private List<Map<String, Object>> convert(Map<String, Object> raw) {
        String role = lower(raw.get("role"));
        String content = stringValue(raw.get("content"));
        if (isInternalLogMessage(raw, content)) return List.of();
        String toolCallId = stringValue(raw.get("toolCallId"));
        String id = stringValue(raw.get("id"));

        if ("assistant".equals(role) && content.contains("[tool_call:")) {
            ToolCall call = parseToolCall(content, toolCallId);
            if (call == null) return optional(textMessage(raw, role, stripToolCall(content)));
            if (FrontendToolCompat.isInternalTool(call.name)) {
                return optional(textMessage(raw, role, stripToolCall(content)));
            }
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> prefix = textMessage(raw, role, stripToolCall(content));
            if (prefix != null) messages.add(prefix);
            messages.add(toolMessage(id, "assistant", "plugin_call", call.name, call.callId, call.arguments, null));
            return messages;
        }
        if ("tool".equals(role) && content.startsWith("[tool_result:")) {
            ToolResult result = parseToolResult(content, toolCallId);
            if (FrontendToolCompat.isInternalTool(result.name)) return List.of();
            return List.of(toolMessage(id, "tool", "plugin_call_output", result.name, result.callId, null, result.output));
        }
        return optional(textMessage(raw, role, restoreUserText(content, role)));
    }

    private List<Map<String, Object>> optional(Map<String, Object> message) {
        return message == null ? List.of() : List.of(message);
    }

    private Map<String, Object> textMessage(Map<String, Object> raw, String role, String content) {
        if (role == null || role.isBlank()) role = "assistant";
        if (content == null || content.isBlank()) return null;
        Map<String, Object> message = baseMessage(raw, role);
        message.put("type", "message");
        message.put("content", List.of(Map.of("type", "text", "text", content, "status", "completed")));
        return message;
    }

    private Map<String, Object> toolMessage(String id, String role, String type, String name,
                                            String callId, Object arguments, Object output) {
        return toolMessage(id, role, type, name, callId, arguments, output, null);
    }

    private Map<String, Object> toolMessage(String id, String role, String type, String name,
                                            String callId, Object arguments, Object output,
                                            Map<String, Object> metadata) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", FrontendToolCompat.displayToolName(name != null && !name.isBlank() ? name : "unknown"));
        data.put("call_id", callId != null && !callId.isBlank() ? callId : id);
        if (arguments != null) data.put("arguments", arguments);
        if (output != null) data.put("output", output);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "data");
        content.put("object", "content");
        content.put("delta", false);
        content.put("index", null);
        content.put("status", "completed");
        content.put("data", data);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id != null && !id.isBlank() ? id : "tool_" + data.get("call_id"));
        message.put("object", "message");
        message.put("role", role);
        message.put("type", type);
        message.put("status", "completed");
        message.put("content", List.of(content));
        if (metadata != null) message.put("metadata", metadata);
        return message;
    }

    private Map<String, Object> startStateMessage(Map<String, Object> raw, String type, String role,
                                                  Map<String, Object> metadata, int index) {
        Map<String, Object> message = runtimeMessage(rawId(raw) + "_" + type + "_" + index, type, role, metadata);
        message.put("content", new ArrayList<Map<String, Object>>());
        return message;
    }

    private Map<String, Object> runtimeMessage(String id, String type, String role, Map<String, Object> metadata) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id == null || id.isBlank() ? "msg_" + UUID.randomUUID().toString().replace("-", "") : id);
        message.put("object", "message");
        message.put("role", role);
        message.put("type", type);
        message.put("status", "completed");
        message.put("metadata", metadata);
        return message;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentList(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("content");
    }

    private Map<String, Object> textContent(String text) {
        Map<String, Object> content = contentBase("text");
        content.put("text", text != null ? text : "");
        return content;
    }

    private Map<String, Object> contentFromBlock(Map<String, Object> block, String blockType, String role) {
        if ("text".equals(blockType)) return textContent(stripInjectedSkillBlock(stringValue(block.get("text")), role));
        Map<String, Object> content = contentBase(blockType);
        if ("image".equals(blockType)) {
            content.put("image_url", resolveMediaUrl(block));
        } else if ("audio".equals(blockType)) {
            content.put("data", resolveMediaUrl(block));
        } else if ("video".equals(blockType)) {
            content.put("video_url", resolveMediaUrl(block));
        } else if ("file".equals(blockType)) {
            content.put("filename", stringValue(block.get("filename")));
            content.put("file_url", resolveMediaUrl(block));
        }
        return content;
    }

    private Map<String, Object> contentBase(String type) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", type);
        content.put("object", "content");
        content.put("delta", false);
        content.put("index", null);
        content.put("status", "completed");
        return content;
    }

    private String normalizedBlockType(Map<String, Object> block) {
        String type = stringValue(block.get("type"));
        if (!"data".equals(type)) return type.isBlank() ? "text" : type;
        Object source = block.get("source");
        if (source instanceof Map<?, ?> sourceMap) {
            String mediaType = stringValue(asMap(sourceMap).get("media_type"));
            if (mediaType.startsWith("image/")) return "image";
            if (mediaType.startsWith("audio/")) return "audio";
            if (mediaType.startsWith("video/")) return "video";
        }
        return "file";
    }

    private boolean isMediaBlock(String type) {
        return "image".equals(type) || "audio".equals(type) || "video".equals(type) || "file".equals(type);
    }

    private String resolveMediaUrl(Map<String, Object> block) {
        String type = stringValue(block.get("type"));
        if ("image".equals(type) && block.get("image_url") != null) return stringValue(block.get("image_url"));
        if ("audio".equals(type) && block.get("data") != null) return stringValue(block.get("data"));
        if ("video".equals(type) && block.get("video_url") != null) return stringValue(block.get("video_url"));
        if ("file".equals(type) && block.get("file_url") != null) return stringValue(block.get("file_url"));
        Object source = block.get("source");
        if (source instanceof String text) return text;
        if (source instanceof Map<?, ?> sourceMap) {
            Map<String, Object> sourceObj = asMap(sourceMap);
            if (sourceObj.get("url") != null) return resolveLocalFileUrl(stringValue(sourceObj.get("url")));
            if (sourceObj.get("data") != null) {
                String mediaType = stringValue(sourceObj.get("media_type"));
                return "data:" + (mediaType.isBlank() ? "application/octet-stream" : mediaType)
                        + ";base64," + sourceObj.get("data");
            }
        }
        return "";
    }

    private String resolveLocalFileUrl(String url) {
        if (url == null || !url.startsWith("file:")) return url;
        try {
            return Path.of(URI.create(url)).toString();
        } catch (Exception ignored) {
            return url.replaceFirst("^file:/+", "/");
        }
    }

    private Map<String, Object> stateMetadata(Map<String, Object> raw) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("original_id", raw.get("id"));
        metadata.put("original_name", raw.get("name"));
        metadata.put("metadata", raw.get("metadata") instanceof Map<?, ?> map ? asMap(map) : Map.of());
        metadata.put("timestamp", raw.get("timestamp"));
        return metadata;
    }

    private boolean isInternalStateMessage(Map<String, Object> raw) {
        if ("user".equals(lower(raw.get("role")))
                && contentContainsExplicitSkillPrompt(raw.get("content"))) {
            return false;
        }
        return ConversationVisibility.isModelOnly(raw);
    }

    private boolean isInternalLogMessage(Map<String, Object> raw, String content) {
        Map<String, Object> normalized = new LinkedHashMap<>(raw);
        normalized.putIfAbsent("content", content);
        return ConversationVisibility.isModelOnly(normalized);
    }

    private boolean isInternalFrontendMessage(Map<String, Object> message) {
        if (ConversationVisibility.isModelOnly(message)) return true;
        Object metadataRaw = message.get("metadata");
        if (metadataRaw instanceof Map<?, ?> metadata
                && ConversationVisibility.isInternalName(stringValue(metadata.get("original_name")))) {
            return true;
        }
        return ConversationVisibility.startsWithInternalEnvelope(
                ConversationVisibility.firstText(message.get("content")));
    }

    private String rawId(Map<String, Object> raw) {
        String id = stringValue(raw.get("id"));
        return id.isBlank() ? "msg_" + UUID.randomUUID().toString().replace("-", "") : id;
    }

    private Object jsonStringOrRaw(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) return JsonUtils.toJson(value);
        return value;
    }

    private Object toolArgumentsJson(String toolName, Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return FrontendToolCompat.argumentsJson(toolName, JsonUtils.toJson(value));
        }
        return value;
    }

    private Map<String, Object> asMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> baseMessage(Map<String, Object> raw, String role) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", stringValue(raw.get("id")));
        message.put("object", "message");
        message.put("role", role);
        message.put("status", "completed");
        message.put("metadata", Map.of("timestamp", timestamp(raw.get("timestamp"))));
        return message;
    }

    private ToolCall parseToolCall(String content, String fallbackCallId) {
        int marker = content.indexOf("[tool_call:");
        if (marker < 0) return null;
        int nameStart = marker + "[tool_call:".length();
        int argsStart = content.indexOf('(', nameStart);
        int argsEnd = content.lastIndexOf(")]");
        if (argsStart < 0 || argsEnd < argsStart) return null;

        String name = content.substring(nameStart, argsStart).trim();
        String argsText = content.substring(argsStart + 1, argsEnd).trim();
        Object args = FrontendToolCompat.argumentsJson(name, argsText);
        return new ToolCall(name, fallbackCallId, args);
    }

    private ToolResult parseToolResult(String content, String fallbackCallId) {
        int marker = content.indexOf("[tool_result:");
        int end = content.indexOf(']', marker);
        String name = "unknown";
        if (marker >= 0 && end > marker) {
            name = content.substring(marker + "[tool_result:".length(), end).trim();
        }
        String output = end >= 0 ? content.substring(end + 1).trim() : content;
        if (output.length() >= 2 && output.startsWith("\"") && output.endsWith("\"")) {
            try {
                output = JsonUtils.getMapper().readValue(output, String.class);
            } catch (IOException ignored) {
                output = output.substring(1, output.length() - 1);
            }
        }
        return new ToolResult(name, fallbackCallId, output);
    }

    private String stripToolCall(String content) {
        int marker = content.indexOf("[tool_call:");
        return marker >= 0 ? content.substring(0, marker).trim() : content;
    }

    private String stripInjectedSkillBlock(String text, String role) {
        String restored = restoreUserText(text, role);
        if (ExplicitSkillInvocation.isModelPrompt(text)) return restored;
        if (!"user".equals(role) || text == null || !text.contains("<skill")) return text;
        return text.replaceFirst("(?s)\\s*<skill\\b[^>]*>.*</skill>\\s*$", "");
    }

    private String restoreUserText(String text, String role) {
        return "user".equals(role) ? ExplicitSkillInvocation.userVisibleText(text) : text;
    }

    private boolean contentContainsExplicitSkillPrompt(Object content) {
        if (content instanceof String text) return ExplicitSkillInvocation.isModelPrompt(text);
        if (!(content instanceof List<?> blocks)) return false;
        for (Object item : blocks) {
            if (item instanceof Map<?, ?> block
                    && ExplicitSkillInvocation.isModelPrompt(stringValue(block.get("text")))) {
                return true;
            }
        }
        return false;
    }

    private String timestamp(Object value) {
        if (value instanceof Number number) {
            return Instant.ofEpochMilli((long) (number.doubleValue() * 1000)).toString();
        }
        return Instant.now().toString();
    }

    private String lower(Object value) {
        String text = stringValue(value);
        return text == null ? "" : text.toLowerCase();
    }

    private record ToolCall(String name, String callId, Object arguments) {}

    private record ToolResult(String name, String callId, Object output) {}
}
