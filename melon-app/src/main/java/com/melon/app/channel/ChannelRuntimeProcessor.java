package com.melon.app.channel;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import com.melon.app.runner.MelonPawEnvelopeMapper;
import com.melon.app.task.EnterpriseTaskService;
import com.melon.app.config.RuntimeExecutors;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelInboundMessage;
import com.melon.channels.ChannelMessageRenderer;
import com.melon.channels.ChannelOutboundMessage;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.OutboundNetworkPolicy;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
public class ChannelRuntimeProcessor {

    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L;

    private final AgentRunner agentRunner;
    private final ChatManager chatManager;
    private final ConfigManager configManager;
    private final ChannelConfigService channelConfigService;
    private final EnterpriseTaskService taskService;
    private final Executor executor;
    private final ChannelExecutionIdentityResolver executionIdentityResolver;
    private final ChannelMessageRenderer messageRenderer = new ChannelMessageRenderer();

    public ChannelRuntimeProcessor(AgentRunner agentRunner, ChatManager chatManager,
                                   ConfigManager configManager, ChannelConfigService channelConfigService) {
        this(agentRunner, chatManager, configManager, channelConfigService, null, Runnable::run, null);
    }

    public ChannelRuntimeProcessor(AgentRunner agentRunner, ChatManager chatManager,
                                   ConfigManager configManager, ChannelConfigService channelConfigService,
                                   EnterpriseTaskService taskService) {
        this(agentRunner, chatManager, configManager, channelConfigService, taskService, Runnable::run, null);
    }

    @Autowired
    public ChannelRuntimeProcessor(AgentRunner agentRunner, ChatManager chatManager,
                                   ConfigManager configManager, ChannelConfigService channelConfigService,
                                   EnterpriseTaskService taskService, RuntimeExecutors executors,
                                   ChannelExecutionIdentityResolver executionIdentityResolver) {
        this(agentRunner, chatManager, configManager, channelConfigService, taskService,
                executors.channel(), executionIdentityResolver);
    }

    private ChannelRuntimeProcessor(AgentRunner agentRunner, ChatManager chatManager,
                                    ConfigManager configManager, ChannelConfigService channelConfigService,
                                    EnterpriseTaskService taskService, Executor executor,
                                    ChannelExecutionIdentityResolver executionIdentityResolver) {
        this.agentRunner = agentRunner;
        this.chatManager = chatManager;
        this.configManager = configManager;
        this.channelConfigService = channelConfigService;
        this.taskService = taskService;
        this.executor = executor;
        this.executionIdentityResolver = executionIdentityResolver;
    }

    public CompletableFuture<ChannelOutboundMessage> process(ChannelInboundMessage inbound) {
        return CompletableFuture.supplyAsync(() -> run(inbound), executor);
    }

    private ChannelOutboundMessage run(ChannelInboundMessage inbound) {
        String agentId = value(inbound.getAgentId(), "default");
        String channel = value(inbound.getChannel(), "console");
        String channelUserId = value(inbound.getUserId(), "default");
        String userId = executionIdentityResolver != null
                ? executionIdentityResolver.resolve(agentId, inbound.getExecutionUserId())
                : value(inbound.getExecutionUserId(), channelUserId);
        String sessionId = value(inbound.getSessionId(), channel + "-" + userId);
        Map<String, Object> channelConfig = channelConfigService.runtimeConfig(agentId, channel);
        inbound.setAttachments(normalizeAttachments(agentId, inbound));
        String text = agentText(inbound);
        List<Map<String, Object>> frontendContext = new ArrayList<>();
        frontendContext.add(userMessage(inbound.getContent(), inbound.getAttachments()));
        ChatSpec chat = chatManager.getOrCreateForSession(agentId, sessionId, userId, channel, title(text));
        Map<String, Object> channelMeta = inbound.getChannelMeta() != null ? inbound.getChannelMeta() : Map.of();
        String source = value(stringValue(channelMeta.get("source")), channel);
        Object triggerRaw = firstPresent(channelMeta, "message_id", "msg_id", "feishu_message_id",
                "event_id", "task_id", "update_id");
        String triggerRef = stringValue(triggerRaw);
        String taskType = "cron".equalsIgnoreCase(source) || "heartbeat".equalsIgnoreCase(source)
                ? "AUTOMATION" : "CHANNEL";
        String idempotencyKey = triggerRef.isBlank() ? "" : channel + ":" + triggerRef;
        EnterpriseTaskService.TaskRun task = taskService != null
                ? taskService.startForAgent(taskType, userId, agentId, chat.getName(), sessionId, source,
                        triggerRef, chat.getId(), idempotencyKey)
                : new EnterpriseTaskService.TaskRun("", "", true);
        if (!task.created()) return outbound(inbound, "", "", task);
        chatManager.setStatus(agentId, chat.getId(), "running");
        MelonPawEnvelopeMapper envelope = new MelonPawEnvelopeMapper(sessionId, task.taskId(), task.runId());
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("agent_id", agentId);
        env.put("channel", channel);
        env.put("source", source);
        env.put("session_id", sessionId);
        env.put("working_dir", configManager.resolveWorkspaceDir(agentId).toString());
        env.put("channel_meta", inbound.getChannelMeta());
        env.put("task_id", task.taskId());
        env.put("run_id", task.runId());
        if (taskService != null) {
            taskService.setRetryIntent(task, new EnterpriseTaskService.RetryIntent("STREAM", text, env));
        }
        try {
            agentRunner.stream(agentId, List.of(new UserMessage(text)), userId, sessionId, env)
                    .doOnNext(envelope::translate)
                    .blockLast();
            envelope.finish();
            String reply = messageRenderer.render(envelope.outputMessagesSnapshot(), channelConfig);
            if (taskService != null) {
                taskService.succeed(task, reply.isBlank() ? envelope.visibleAssistantText() : reply);
            }
            return outbound(inbound, reply.isBlank() ? envelope.visibleAssistantText() : reply,
                    envelope.visibleAssistantText(), task);
        } catch (Exception e) {
            envelope.error(e);
            if (taskService != null) taskService.fail(task, e);
            return outbound(inbound, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), "", task);
        } finally {
            chatManager.setStatus(agentId, chat.getId(), "idle");
            chatManager.saveSessionShadowFromStateStore(agentId, channel, userId, sessionId,
                    sessionContext(frontendContext, envelope.outputStateMessagesSnapshot()), envelope.turnUsageSnapshot());
        }
    }

    private ChannelOutboundMessage outbound(ChannelInboundMessage inbound, String text, String visibleText,
                                            EnterpriseTaskService.TaskRun task) {
        ChannelOutboundMessage out = new ChannelOutboundMessage();
        out.setAgentId(inbound.getAgentId());
        out.setChannel(inbound.getChannel());
        out.setUserId(inbound.getUserId());
        out.setSessionId(inbound.getSessionId());
        out.setTo(inbound.getReplyTo());
        out.setText(text != null ? text : "");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("channel_meta", inbound.getChannelMeta() != null ? inbound.getChannelMeta() : Map.of());
        meta.put("visible_text", visibleText != null ? visibleText : "");
        if (task != null && !task.taskId().isBlank()) {
            meta.put("task_id", task.taskId());
            meta.put("run_id", task.runId());
        }
        out.setMeta(meta);
        return out;
    }

    private Map<String, Object> userMessage(String text, List<Map<String, Object>> attachments) {
        java.util.ArrayList<Map<String, Object>> content = new java.util.ArrayList<>();
        content.add(Map.of("type", "text", "text", text != null ? text : ""));
        for (Map<String, Object> attachment : attachments != null ? attachments : List.<Map<String, Object>>of()) {
            Map<String, Object> block = new LinkedHashMap<>(attachment);
            block.putIfAbsent("type", "data");
            content.add(block);
        }
        return new LinkedHashMap<>(Map.of(
                "id", "channel_user_" + UUID.randomUUID().toString().replace("-", ""),
                "role", "user",
                "name", "user",
                "content", content
        ));
    }

    private List<Map<String, Object>> sessionContext(List<Map<String, Object>> userMessages,
                                                     List<Map<String, Object>> outputMessages) {
        List<Map<String, Object>> context = new ArrayList<>(userMessages != null ? userMessages : List.of());
        if (outputMessages != null) context.addAll(outputMessages);
        return context;
    }

    private String agentText(ChannelInboundMessage inbound) {
        StringBuilder text = new StringBuilder(inbound.getContent() != null ? inbound.getContent() : "");
        for (Map<String, Object> attachment : inbound.getAttachments() != null ? inbound.getAttachments() : List.<Map<String, Object>>of()) {
            Object path = firstPresent(attachment, "path", "url", "file_url", "image_url", "name", "filename");
            if ((path == null || String.valueOf(path).isBlank()) && attachment.get("source") instanceof Map<?, ?> source) {
                path = firstPresent(toStringMap(source), "url", "data");
            }
            if (path != null && !String.valueOf(path).isBlank()) {
                text.append("\n用户上传文件，已经下载到 ").append(filePathText(String.valueOf(path)));
            }
        }
        return text.toString();
    }

    private List<Map<String, Object>> normalizeAttachments(String agentId, ChannelInboundMessage inbound) {
        List<Map<String, Object>> raw = new ArrayList<>(inbound.getAttachments() != null ? inbound.getAttachments() : List.of());
        raw.addAll(extractAttachments(inbound.getChannelMeta()));
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> attachment : raw) {
            Map<String, Object> block = normalizeAttachment(agentId, attachment);
            if (!block.isEmpty()) normalized.add(block);
        }
        return normalized;
    }

    private List<Map<String, Object>> extractAttachments(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : List.of("attachments", "files", "images", "videos", "audios")) {
            Object value = meta.get(key);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) result.add(toStringMap(map));
                }
            }
        }
        if (firstPresent(meta, "file_url", "image_url", "video_url", "audio_url", "download_url", "media_url",
                "url_private_download", "url_private", "url_download") != null) {
            result.add(new LinkedHashMap<>(meta));
        }
        return result;
    }

    private Map<String, Object> normalizeAttachment(String agentId, Map<String, Object> attachment) {
        if (attachment == null || attachment.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>(attachment);
        String name = fileName(copy);
        String mediaType = mediaType(copy, name);
        String url = sourceUrl(copy);
        String path = "";
        if (isRemote(url)) {
            path = downloadRemote(agentId, url, name, copy);
            copy.put("original_url", url);
        } else if (!url.isBlank()) {
            path = filePathText(url);
        }
        if (path.isBlank()) path = stringValue(firstPresent(copy, "path", "local_path"));
        if (path.isBlank() && url.isBlank()) return copy;
        String fileUrl = toFileUrl(path.isBlank() ? url : path);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "data");
        block.put("name", name);
        block.put("filename", name);
        if (!path.isBlank()) block.put("path", path);
        block.put("source", Map.of("type", "url", "url", fileUrl, "media_type", mediaType));
        if (copy.get("original_url") != null) block.put("original_url", copy.get("original_url"));
        return block;
    }

    private String downloadRemote(String agentId, String url, String name,
                                  Map<String, Object> attachment) {
        Path target = null;
        try {
            URI remoteUri = validatedRemoteUri(url);
            Path mediaDir = mediaDir(agentId);
            Files.createDirectories(mediaDir);
            target = mediaDir.resolve(UUID.randomUUID().toString().replace("-", "") + "_" + safeFileName(name)).normalize();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(remoteUri)
                    .timeout(java.time.Duration.ofSeconds(45))
                    .GET();
            HttpRequest request = requestBuilder.build();
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) return url;
                long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declaredLength > MAX_ATTACHMENT_BYTES) return url;
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    long total = 0L;
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        total += read;
                        if (total > MAX_ATTACHMENT_BYTES) {
                            throw new IllegalArgumentException("Channel attachment exceeds 25 MiB");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
            decryptAttachmentIfRequired(target, attachment);
            return target.toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            if (target != null) {
                try {
                    Files.deleteIfExists(target);
                } catch (Exception ignoredDelete) {
                    // Best-effort cleanup of a rejected or partial download.
                }
            }
            return url;
        }
    }

    void decryptAttachmentIfRequired(Path target, Map<String, Object> attachment) throws Exception {
        String aesKey = stringValue(firstPresent(attachment, "aes_key", "aeskey"));
        if (aesKey.isBlank()) return;
        String encryption = stringValue(attachment.get("encryption"));
        if (!"aes-256-cbc".equalsIgnoreCase(encryption)
                && !"aes-ecb-pkcs7".equalsIgnoreCase(encryption)) {
            throw new IllegalArgumentException("Unsupported channel attachment encryption: " + encryption);
        }
        boolean cbc = "aes-256-cbc".equalsIgnoreCase(encryption);
        byte[] key = cbc ? Base64.getDecoder().decode(aesKey) : decodeAttachmentAesKey(aesKey);
        if (cbc && key.length != 32) {
            throw new IllegalArgumentException("AES-256-CBC channel attachment key must be 256 bits");
        }
        byte[] encrypted = Files.readAllBytes(target);
        if (encrypted.length == 0 || encrypted.length % 16 != 0) {
            throw new IllegalArgumentException("Encrypted channel attachment has invalid block length");
        }
        Cipher cipher;
        if (cbc) {
            cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(key, 0, 16));
        } else {
            cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        }
        byte[] decrypted = cipher.doFinal(encrypted);
        int padding = Byte.toUnsignedInt(decrypted[decrypted.length - 1]);
        if (padding < 1 || padding > 32 || padding > decrypted.length) {
            throw new IllegalArgumentException("Encrypted channel attachment has invalid PKCS#7 padding");
        }
        for (int i = decrypted.length - padding; i < decrypted.length; i++) {
            if (Byte.toUnsignedInt(decrypted[i]) != padding) {
                throw new IllegalArgumentException("Encrypted channel attachment has invalid PKCS#7 padding");
            }
        }
        Files.write(target, java.util.Arrays.copyOf(decrypted, decrypted.length - padding));
    }

    private byte[] decodeAttachmentAesKey(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.matches("(?i)[0-9a-f]{32}|[0-9a-f]{48}|[0-9a-f]{64}")) {
            return java.util.HexFormat.of().parseHex(raw);
        }
        byte[] decoded = Base64.getDecoder().decode(raw);
        String decodedText = new String(decoded, StandardCharsets.US_ASCII);
        if ((decoded.length == 32 || decoded.length == 48 || decoded.length == 64)
                && decodedText.matches("(?i)[0-9a-f]+")) {
            return java.util.HexFormat.of().parseHex(decodedText);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalArgumentException("Channel attachment AES key has invalid length");
        }
        return decoded;
    }

    URI validatedRemoteUri(String url) {
        return OutboundNetworkPolicy.requirePublicHttpUri(url);
    }

    private Path mediaDir(String agentId) {
        return configManager.resolveWorkspaceDir(agentId).resolve("media");
    }

    private String sourceUrl(Map<String, Object> attachment) {
        if (attachment.get("source") instanceof Map<?, ?> source) {
            String value = stringValue(firstPresent(toStringMap(source), "url", "data"));
            if (!value.isBlank()) return value;
        }
        return stringValue(firstPresent(attachment,
                "path", "local_path", "url", "file_url", "image_url", "video_url", "audio_url",
                "download_url", "media_url", "url_private_download", "url_private", "url_download"));
    }

    private String fileName(Map<String, Object> attachment) {
        String name = stringValue(firstPresent(attachment, "file_name", "filename", "name", "title"));
        if (!name.isBlank()) return safeFileName(name);
        String url = sourceUrl(attachment);
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isBlank()) {
                String tail = path.substring(path.lastIndexOf('/') + 1);
                if (!tail.isBlank()) return safeFileName(URLDecoder.decode(tail, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "channel-file";
    }

    private String mediaType(Map<String, Object> attachment, String name) {
        String explicit = stringValue(firstPresent(attachment, "media_type", "mime_type", "content_type", "type"));
        if (explicit.contains("/")) return explicit;
        String lower = name.toLowerCase();
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) return "image/*";
        if (lower.matches(".*\\.(mp4|mov|avi|mkv|webm)$")) return "video/*";
        if (lower.matches(".*\\.(mp3|wav|m4a|ogg|flac)$")) return "audio/*";
        return "application/octet-stream";
    }

    private String filePathText(String value) {
        if (value == null) return "";
        if (value.startsWith("file://")) {
            try {
                return Path.of(URI.create(value)).toString();
            } catch (Exception ignored) {
                return value.substring("file://".length());
            }
        }
        return value;
    }

    private String toFileUrl(String value) {
        if (value == null || value.isBlank() || value.startsWith("file://") || value.startsWith("http://")
                || value.startsWith("https://") || value.startsWith("data:")) {
            return value;
        }
        return Path.of(value).toAbsolutePath().normalize().toUri().toString();
    }

    private boolean isRemote(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private Map<String, Object> toStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isBlank() ? "channel-file" : cleaned;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String title(String text) {
        if (text == null || text.isBlank()) return "Channel message";
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
