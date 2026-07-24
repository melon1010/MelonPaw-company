package com.melon.channels;

import com.melon.core.util.JsonUtils;
import com.melon.core.util.OutboundNetworkPolicy;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringValue;

final class ChannelHttpSupport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+|www\\.\\S+", Pattern.CASE_INSENSITIVE);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ChannelHttpSupport() {
    }

    static HttpResponse<String> get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(OutboundNetworkPolicy.requirePublicHttpUri(url))
                .timeout(Duration.ofSeconds(45))
                .GET();
        if (headers != null) headers.forEach(builder::header);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String url, Map<String, String> headers, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(OutboundNetworkPolicy.requirePublicHttpUri(url))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)));
        if (headers != null) headers.forEach(builder::header);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, Object> json(String text) {
        Map<String, Object> map = JsonUtils.fromJson(text, Map.class);
        return map != null ? map : Map.of();
    }

    static Map<String, Object> copy(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw != null) raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    static Map<?, ?> map(Object raw) {
        return raw instanceof Map<?, ?> map ? map : Map.of();
    }

    static List<?> list(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    static String first(Map<?, ?> map, String... keys) {
        if (map == null) return "";
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    static List<String> missing(Map<String, Object> config, String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (stringValue(config.get(key)).isBlank()) result.add(key);
        }
        return result;
    }

    static String target(ChannelOutboundMessage message, String... keys) {
        String id = message.getTo() != null ? stringValue(message.getTo().getId()) : "";
        if (!id.isBlank() && !"default".equals(id)) return id;
        Map<?, ?> extra = message.getTo() != null ? message.getTo().getExtra() : Map.of();
        String value = first(extra, keys);
        if (!value.isBlank()) return value;
        Map<?, ?> meta = map(message.getMeta().get("channel_meta"));
        value = first(meta, keys);
        if (!value.isBlank()) return value;
        return message.getUserId();
    }

    static void mark(ChannelOutboundMessage message, String status, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>(message.getMeta());
        meta.put("delivery_status", status);
        meta.put("delivery_detail", detail);
        message.setMeta(meta);
    }

    static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte b : data) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static String sanitizeQqText(String text) {
        return URL_PATTERN.matcher(text != null ? text : "").replaceAll("[链接已省略]");
    }

    static String qrPngBase64(String text) {
        try {
            var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240);
            var image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("QR code generation failed", e);
        }
    }

    static String aesGcmDecryptBase64(String encryptedBase64, String keyBase64) {
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            byte[] raw = Base64.getDecoder().decode(encryptedBase64);
            if (raw.length < 28) throw new IllegalArgumentException("Ciphertext too short");
            byte[] iv = java.util.Arrays.copyOfRange(raw, 0, 12);
            byte[] cipherText = java.util.Arrays.copyOfRange(raw, 12, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

class WeChatChannelAdapter extends BasicChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(WeChatChannelAdapter.class);
    private static final String DEFAULT_BASE = "https://ilinkai.weixin.qq.com";
    private static final String VERSION = "2.0.1";
    private static final int PROCESSED_LIMIT = 2000;

    private final Executor worker;
    private final ScheduledExecutorService executor;
    private final ExecutorService longPollExecutor;
    private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPollSuccess = new ConcurrentHashMap<>();
    private final Map<String, String> lastPollErrors = new ConcurrentHashMap<>();
    private final Map<String, String> cursors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> processed = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> contextTokens = new ConcurrentHashMap<>();
    private final Map<String, Path> stateDirs = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();
    private final Map<String, TypingTicket> typingTickets = new ConcurrentHashMap<>();

    WeChatChannelAdapter(Executor worker, ScheduledExecutorService executor,
                         ExecutorService longPollExecutor) {
        super("wechat", true, true, false, false);
        this.worker = worker;
        this.executor = executor;
        this.longPollExecutor = longPollExecutor;
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        Map<String, Object> runtime = new LinkedHashMap<>(config != null ? config : Map.of());
        List<String> missing = ChannelHttpSupport.missing(runtime, "bot_token");
        if (!Boolean.TRUE.equals(runtime.get("enabled")) || !missing.isEmpty()) {
            log.info("WeChat channel not started: agent={}, enabled={}, missing={}",
                    agentId, runtime.get("enabled"), missing);
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        super.start(agentId, runtime);
        stateDirs.put(key(agentId), channelStateDir(runtime));
        loadContextTokens(agentId, runtime);
        String key = key(agentId);
        Future<?> existing = tasks.get(key);
        if (existing != null && !existing.isCancelled() && !existing.isDone()) {
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        String generation = UUID.randomUUID().toString();
        active.put(key, generation);
        lastPollErrors.remove(key);
        lastPollSuccess.remove(key);
        try {
            Future<?> task = longPollExecutor.submit(
                    () -> pollLoop(agentId, generation, new LinkedHashMap<>(runtime), dispatcher));
            tasks.put(key, task);
        } catch (RejectedExecutionException e) {
            active.remove(key, generation);
            lastPollErrors.put(key, "WeChat long-poll capacity reached");
            super.stop(agentId);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "WeChat long-poll capacity reached; increase MELON_CHANNEL_LONG_POLL_THREADS", e));
        }
        log.info("WeChat channel started: agent={}, base={}", agentId, base(runtime));
        return CompletableFuture.completedFuture(health(agentId, runtime));
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        String key = key(agentId);
        active.remove(key);
        Future<?> task = tasks.remove(key);
        if (task != null) task.cancel(true);
        typingTasks.forEach((taskKey, typing) -> {
            if (taskKey.startsWith(agentId + ":") && typing != null) typing.cancel(true);
        });
        typingTasks.keySet().removeIf(taskKey -> taskKey.startsWith(agentId + ":"));
        return super.stop(agentId);
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        Map<String, Object> runtime = new LinkedHashMap<>(config != null ? config : Map.of());
        if (!Boolean.TRUE.equals(runtime.get("enabled"))) {
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        }
        List<String> missing = ChannelHttpSupport.missing(runtime, "bot_token");
        if (!missing.isEmpty()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: bot_token");
        }
        String key = key(agentId);
        Future<?> task = tasks.get(key);
        boolean running = task != null && !task.isCancelled() && !task.isDone();
        String error = lastPollErrors.get(key);
        if (!running) {
            return ChannelHealth.of(type(), error == null ? "configured" : "unhealthy", false,
                    error == null, true, error == null
                            ? "WeChat is configured but long-poll is not running."
                            : error);
        }
        if (lastPollSuccess.containsKey(key)) {
            return ChannelHealth.of(type(), "running", true, true, true,
                    "WeChat client is connected and polling.");
        }
        return ChannelHealth.of(type(), error == null ? "starting" : "reconnecting", false,
                error == null, true, error == null
                        ? "WeChat long-poll is starting."
                        : error);
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        try {
            String base = base(config);
            HttpResponse<String> response = ChannelHttpSupport.get(base + "/ilink/bot/get_bot_qrcode?bot_type=3", headers(Map.of()));
            if (!ok(response)) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false,
                        "status", "failed", "detail", "WeChat QR code HTTP " + response.statusCode());
            }
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            String qrcode = stringValue(body.get("qrcode"));
            String qrcodeContent = stringValue(body.get("qrcode_img_content"));
            if (qrcode.isBlank()) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false,
                        "status", "failed", "detail", "WeChat returned an empty QR polling token");
            }
            String scanUrl = qrcodeContent.startsWith("http")
                    ? qrcodeContent
                    : "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=" + ChannelHttpSupport.encode(qrcode) + "&bot_type=3";
            return Map.of(
                    "qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl),
                    "poll_token", qrcode,
                    "enabled", true,
                    "status", response.statusCode() >= 200 && response.statusCode() < 300 ? "waiting" : "failed",
                    "detail", response.body()
            );
        } catch (Exception e) {
            return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        try {
            String base = base(config);
            HttpResponse<String> response = ChannelHttpSupport.get(base + "/ilink/bot/get_qrcode_status?qrcode=" + ChannelHttpSupport.encode(token), headers(Map.of()));
            if (!ok(response)) {
                return Map.of("status", "fail", "credentials", Map.of(),
                        "detail", "WeChat QR status HTTP " + response.statusCode());
            }
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            Map<String, Object> credentials = new LinkedHashMap<>();
            credentials.put("bot_token", body.getOrDefault("bot_token", ""));
            credentials.put("base_url", body.getOrDefault("baseurl", base));
            String status = stringValue(body.get("status"), "unknown");
            return Map.of("status", status, "credentials", credentials, "detail", body);
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of(), "detail", e.getMessage());
        }
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        return inbound(agentId, body, headers);
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.supplyAsync(() -> {
            String toUser = "";
            try {
                toUser = ChannelHttpSupport.target(message, "to_user_id", "from_user_id", "user_id");
                Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
                String contextToken = ChannelHttpSupport.first(meta, "context_token", "wechat_context_token");
                if (contextToken.isBlank() && message.getTo() != null) {
                    contextToken = ChannelHttpSupport.first(message.getTo().getExtra(), "context_token", "wechat_context_token");
                }
                if (contextToken.isBlank()) {
                    contextToken = contextTokens.getOrDefault(key(message.getAgentId()), Map.of())
                            .getOrDefault(toUser, "");
                }
                if (contextToken.isBlank()) {
                    log.warn("WeChat send without context_token: agent={}, user={}, session={}",
                            message.getAgentId(), message.getUserId(), message.getSessionId());
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("from_user_id", "");
                msg.put("to_user_id", toUser);
                msg.put("client_id", UUID.randomUUID().toString());
                msg.put("message_type", 2);
                msg.put("message_state", 2);
                msg.put("context_token", contextToken);
                msg.put("item_list", List.of(Map.of("type", 1, "text_item", Map.of("text", message.getText()))));
                HttpResponse<String> response = ChannelHttpSupport.post(base(config) + "/ilink/bot/sendmessage",
                        headers(config), Map.of("msg", msg, "base_info", Map.of("channel_version", VERSION)));
                boolean sent = ok(response) && accepted(response.body());
                ChannelHttpSupport.mark(message, sent ? "sent" : "failed", response.statusCode() + " " + response.body());
                if (!sent) {
                    if (contextTokenInvalid(response.body())) clearContextToken(message.getAgentId(), toUser, config);
                    log.warn("WeChat send failed: agent={}, to={}, session={}, detail={}",
                            message.getAgentId(), toUser, message.getSessionId(), response.statusCode() + " " + response.body());
                } else {
                    log.info("WeChat outbound sent: agent={}, to={}, session={}, text_len={}",
                            message.getAgentId(), toUser, message.getSessionId(), message.getText().length());
                }
            } catch (Exception e) {
                ChannelHttpSupport.mark(message, "failed", e.getMessage());
                log.warn("WeChat send exception: agent={}, user={}, session={}, error={}",
                        message.getAgentId(), message.getUserId(), message.getSessionId(), e.toString());
            } finally {
                stopTyping(message.getAgentId(), toUser, message.getSessionId(), config);
            }
            return message;
        }, worker);
    }

    private void pollLoop(String agentId, String generation, Map<String, Object> config,
                          ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        try {
            while (generation.equals(active.get(key)) && !Thread.currentThread().isInterrupted()) {
                PollOutcome outcome = poll(agentId, generation, config, dispatcher);
                if (outcome == PollOutcome.STOP) break;
                if (outcome == PollOutcome.RETRY) ChannelHttpSupport.sleep(1500);
            }
        } finally {
            active.remove(key, generation);
        }
    }

    private PollOutcome poll(String agentId, String generation, Map<String, Object> config,
                             ChannelInboundDispatcher dispatcher) {
        try {
            String cursor = cursors.getOrDefault(key(agentId), "");
            Map<String, Object> body = Map.of(
                    "get_updates_buf", cursor,
                    "base_info", Map.of("channel_version", VERSION));
            HttpResponse<String> response = ChannelHttpSupport.post(base(config) + "/ilink/bot/getupdates", headers(config), body);
            if (!generation.equals(active.get(key(agentId)))) return PollOutcome.STOP;
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                lastPollErrors.put(key(agentId), "WeChat bot token is invalid or expired");
                return PollOutcome.STOP;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("WeChat getupdates HTTP " + response.statusCode());
            }
            Map<String, Object> data = ChannelHttpSupport.json(response.body());
            int ret = intValue(data.get("ret"));
            if (botTokenInvalid(response.body())) {
                String detail = "WeChat bot token is invalid or expired";
                lastPollErrors.put(key(agentId), detail);
                log.warn("{}: agent={}", detail, agentId);
                return PollOutcome.STOP;
            }
            String next = stringValue(data.get("get_updates_buf"));
            if (!next.isBlank()) cursors.put(key(agentId), next);
            List<?> messages = ChannelHttpSupport.list(data.get("msgs"));
            if (ret != 0 && !messages.isEmpty()) {
                log.warn("WeChat getupdates returned ret={} with {} message(s): {}", ret, messages.size(), response.body());
            } else if (ret != 0 && ret != -1) {
                log.warn("WeChat getupdates returned ret={}: {}", ret, response.body());
            }
            for (Object item : messages) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> payload = ChannelHttpSupport.copy(raw);
                    if (intValue(payload.get("message_type")) != 1) {
                        log.debug("WeChat skipped non-user message: type={}, context={}",
                                payload.get("message_type"), stringValue(payload.get("context_token")));
                        continue;
                    }
                    if (duplicate(agentId, payload)) {
                        log.debug("WeChat skipped duplicate message: agent={}, context={}, msg={}",
                                agentId, stringValue(payload.get("context_token")), stringValue(payload.get("msg_id")));
                        continue;
                    }
                    ChannelInboundMessage inbound = inbound(agentId, payload, Map.of());
                    if (inbound.getContent().isBlank() && (inbound.getAttachments() == null || inbound.getAttachments().isEmpty())) {
                        log.debug("WeChat skipped empty inbound message: agent={}, user={}, context={}",
                                agentId, inbound.getUserId(), stringValue(payload.get("context_token")));
                        continue;
                    }
                    log.info("WeChat inbound message: agent={}, user={}, session={}, text_len={}",
                            agentId, inbound.getUserId(), inbound.getSessionId(), inbound.getContent().length());
                    startTyping(agentId, inbound, config);
                    dispatcher.dispatch(inbound, 20);
                }
            }
            lastPollSuccess.put(key(agentId), System.currentTimeMillis());
            lastPollErrors.remove(key(agentId));
            return PollOutcome.CONTINUE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PollOutcome.STOP;
        } catch (Exception e) {
            String detail = "WeChat long-poll failed: " + stringValue(e.getMessage(), e.getClass().getSimpleName());
            lastPollErrors.put(key(agentId), detail);
            log.warn("WeChat poll failed: agent={}, base={}, error={}", agentId, base(config), e.toString());
            return PollOutcome.RETRY;
        }
    }

    private ChannelInboundMessage inbound(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        String from = ChannelHttpSupport.first(payload, "from_user_id", "sender_id", "user_id");
        String to = ChannelHttpSupport.first(payload, "to_user_id");
        String contextToken = ChannelHttpSupport.first(payload, "context_token");
        String group = ChannelHttpSupport.first(payload, "group_id", "wechat_group_id");
        List<String> texts = new ArrayList<>();
        List<Map<String, Object>> attachments = new ArrayList<>();
        String direct = ChannelHttpSupport.first(payload, "text", "content", "message");
        if (!direct.isBlank()) texts.add(direct);
        for (Object item : ChannelHttpSupport.list(payload.get("item_list"))) {
            Map<?, ?> part = ChannelHttpSupport.map(item);
            int itemType = part.containsKey("type") ? intValue(part.get("type"))
                    : !ChannelHttpSupport.map(part.get("text_item")).isEmpty() ? 1 : 0;
            if (itemType == 1 || itemType == 0) {
                String value = stringValue(ChannelHttpSupport.map(part.get("text_item")).get("text")).trim();
                if (!value.isBlank() && !looksLikeFileName(value)) texts.add(value);
            } else if (itemType == 3) {
                Map<?, ?> voice = ChannelHttpSupport.map(part.get("voice_item"));
                String asr = stringValue(ChannelHttpSupport.map(voice.get("text_item")).get("text"),
                        stringValue(voice.get("text"))).trim();
                if (!asr.isBlank()) {
                    texts.add(asr);
                } else {
                    Map<String, Object> attachment = wechatAttachment(part, itemType);
                    if (!attachment.isEmpty()) attachments.add(attachment);
                }
            } else {
                Map<String, Object> attachment = wechatAttachment(part, itemType);
                if (!attachment.isEmpty()) attachments.add(attachment);
            }
        }
        String text = String.join("\n", texts).trim();
        Map<String, Object> meta = new LinkedHashMap<>(payload);
        meta.put("wechat_from_user_id", from);
        meta.put("wechat_to_user_id", to);
        meta.put("wechat_context_token", contextToken);
        meta.put("wechat_group_id", group);
        meta.put("is_group", !group.isBlank());
        if (from != null && !from.isBlank() && contextToken != null && !contextToken.isBlank()) {
            contextTokens.computeIfAbsent(key(agentId), ignored -> new ConcurrentHashMap<>()).put(from, contextToken);
            saveContextTokens(agentId, Map.of(), contextTokens.get(key(agentId)));
        }
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(from);
        inbound.setSessionId(!group.isBlank() ? "wechat:group:" + group : "wechat:" + from);
        inbound.setContent(text);
        inbound.setAttachments(attachments);
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress("dm", from, Map.of(
                "to_user_id", from,
                "context_token", contextToken,
                "wechat_context_token", contextToken,
                "wechat_group_id", group,
                "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private Map<String, Object> wechatAttachment(Map<?, ?> part, int itemType) {
        Map<String, Object> result = new LinkedHashMap<>();
        String mediaType = switch (itemType) {
            case 2 -> "image/jpeg";
            case 3 -> "audio/*";
            case 4 -> "application/octet-stream";
            case 5 -> "video/mp4";
            default -> "";
        };
        String name = switch (itemType) {
            case 2 -> "wechat-image.jpg";
            case 3 -> "wechat-voice.amr";
            case 5 -> "wechat-video.mp4";
            default -> "wechat-file";
        };
        Map<?, ?> item = switch (itemType) {
            case 2 -> ChannelHttpSupport.map(part.get("image_item"));
            case 3 -> ChannelHttpSupport.map(part.get("voice_item"));
            case 4 -> ChannelHttpSupport.map(part.get("file_item"));
            case 5 -> ChannelHttpSupport.map(part.get("video_item"));
            default -> Map.of();
        };
        if (itemType == 4) {
            name = stringValue(item.get("file_name"), name);
        }
        Map<?, ?> media = ChannelHttpSupport.map(item.get("media"));
        String encrypt = ChannelHttpSupport.first(media, "encrypt_query_param");
        String aesKey = ChannelHttpSupport.first(item, "aeskey");
        if (aesKey.isBlank()) aesKey = ChannelHttpSupport.first(media, "aes_key");
        if (encrypt.isBlank()) return Map.of();
        String downloadUrl = "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param="
                + ChannelHttpSupport.encode(encrypt);
        result.put("type", "data");
        result.put("name", name);
        result.put("filename", name);
        result.put("media_type", mediaType);
        result.put("encrypt_query_param", encrypt);
        result.put("aes_key", aesKey);
        result.put("encryption", "aes-ecb-pkcs7");
        result.put("url", downloadUrl);
        result.put("source", Map.of("type", "url", "url", downloadUrl, "media_type", mediaType));
        return result;
    }

    private Map<String, String> headers(Map<String, Object> config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AuthorizationType", "ilink_bot_token");
        headers.put("X-WECHAT-UIN", Base64.getEncoder().encodeToString(
                String.valueOf(randomUint32()).getBytes(StandardCharsets.UTF_8)));
        String token = stringValue(config.get("bot_token"));
        if (!token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private void loadContextTokens(String agentId, Map<String, Object> config) {
        try {
            Path path = contextTokensPath(config);
            if (!Files.isRegularFile(path)) return;
            Map<String, Object> raw = ChannelHttpSupport.json(Files.readString(path, StandardCharsets.UTF_8));
            Map<String, String> tokens = new ConcurrentHashMap<>();
            raw.forEach((k, v) -> {
                String value = stringValue(v);
                if (!value.isBlank()) tokens.put(k, value);
            });
            contextTokens.put(key(agentId), tokens);
            log.info("WeChat loaded {} context_token(s) from {}", tokens.size(), path);
        } catch (Exception e) {
            log.warn("WeChat failed to load context tokens: {}", e.toString());
        }
    }

    private void saveContextTokens(String agentId, Map<String, Object> config, Map<String, String> tokens) {
        try {
            Path stateDir = stateDirs.get(key(agentId));
            if (stateDir == null) stateDir = channelStateDir(config);
            Path path = stateDir.resolve("wechat_context_tokens.json");
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, JsonUtils.toJson(tokens != null ? tokens : Map.of()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("WeChat failed to save context tokens for agent {}: {}", agentId, e.toString());
        }
    }

    private void startTyping(String agentId, ChannelInboundMessage inbound, Map<String, Object> config) {
        String userId = stringValue(inbound.getUserId());
        String contextToken = ChannelHttpSupport.first(inbound.getChannelMeta(), "wechat_context_token", "context_token");
        if (userId.isBlank() || contextToken.isBlank()) return;
        String taskKey = typingKey(agentId, userId, inbound.getSessionId());
        if (typingTasks.containsKey(taskKey)) return;
        String ticket = typingTicket(agentId, userId, contextToken, config);
        if (ticket.isBlank()) return;
        sendTyping(agentId, userId, ticket, 1, config);
        ScheduledFuture<?> task = executor.scheduleWithFixedDelay(
                () -> sendTyping(agentId, userId, ticket, 1, config), 5, 5, TimeUnit.SECONDS);
        typingTasks.put(taskKey, task);
    }

    private void stopTyping(String agentId, String userId, String sessionId, Map<String, Object> config) {
        if (userId == null || userId.isBlank()) return;
        ScheduledFuture<?> task = typingTasks.remove(typingKey(agentId, userId, sessionId));
        if (task != null) task.cancel(false);
        TypingTicket ticket = typingTickets.get(agentId + ":" + userId);
        if (ticket != null && !ticket.expired()) sendTyping(agentId, userId, ticket.value(), 2, config);
    }

    private String typingTicket(String agentId, String userId, String contextToken, Map<String, Object> config) {
        String key = agentId + ":" + userId;
        TypingTicket cached = typingTickets.get(key);
        if (cached != null && !cached.expired()) return cached.value();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ilink_user_id", userId);
            body.put("context_token", contextToken);
            body.put("base_info", Map.of("channel_version", VERSION));
            HttpResponse<String> response = ChannelHttpSupport.post(base(config) + "/ilink/bot/getconfig", headers(config), body);
            Map<String, Object> parsed = ChannelHttpSupport.json(response.body());
            if (ok(response) && intValue(parsed.get("ret")) == 0 && intValue(parsed.get("errcode")) == 0) {
                String ticket = stringValue(parsed.get("typing_ticket"));
                if (!ticket.isBlank()) {
                    typingTickets.put(key, new TypingTicket(ticket, System.currentTimeMillis() + Duration.ofHours(24).toMillis()));
                    return ticket;
                }
            }
            log.debug("WeChat getconfig returned no typing ticket: agent={}, user={}, detail={}", agentId, userId, response.body());
        } catch (Exception e) {
            log.debug("WeChat getconfig failed: agent={}, user={}, error={}", agentId, userId, e.toString());
        }
        return "";
    }

    private void sendTyping(String agentId, String userId, String ticket, int status, Map<String, Object> config) {
        try {
            ChannelHttpSupport.post(base(config) + "/ilink/bot/sendtyping", headers(config), Map.of(
                    "ilink_user_id", userId,
                    "typing_ticket", ticket,
                    "status", status,
                    "base_info", Map.of("channel_version", VERSION)));
        } catch (Exception e) {
            log.debug("WeChat sendtyping failed: agent={}, user={}, status={}, error={}", agentId, userId, status, e.toString());
        }
    }

    private String typingKey(String agentId, String userId, String sessionId) {
        return agentId + ":" + userId + ":" + stringValue(sessionId);
    }

    private void clearContextToken(String agentId, String userId, Map<String, Object> config) {
        if (userId == null || userId.isBlank()) return;
        Map<String, String> tokens = contextTokens.get(key(agentId));
        if (tokens == null) return;
        tokens.remove(userId);
        saveContextTokens(agentId, config, tokens);
    }

    private Path contextTokensPath(Map<String, Object> config) {
        return channelStateDir(config).resolve("wechat_context_tokens.json");
    }

    private Path channelStateDir(Map<String, Object> config) {
        String value = stringValue(config != null ? config.get("_channel_state_dir") : null);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Server-managed channel state directory is required");
        }
        return Path.of(value);
    }

    private long randomUint32() {
        return new SecureRandom().nextLong() & 0xFFFF_FFFFL;
    }

    private boolean accepted(String body) {
        Map<String, Object> parsed = ChannelHttpSupport.json(body);
        if (parsed.isEmpty()) return true;
        return intValue(parsed.get("ret")) == 0 && intValue(parsed.get("errcode")) == 0;
    }

    private boolean contextTokenInvalid(String body) {
        Map<String, Object> parsed = ChannelHttpSupport.json(body);
        return intValue(parsed.get("ret")) == -2;
    }

    private boolean botTokenInvalid(String body) {
        Map<String, Object> parsed = ChannelHttpSupport.json(body);
        int errcode = intValue(parsed.get("errcode"));
        String message = stringValue(parsed.get("errmsg")).toLowerCase();
        return errcode == -14
                || message.contains("session timeout")
                || (message.contains("bot token")
                && (message.contains("invalid") || message.contains("expired")));
    }

    private record TypingTicket(String value, long expiresAtMillis) {
        boolean expired() {
            return value == null || value.isBlank() || System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    private boolean looksLikeFileName(String text) {
        String lower = text.toLowerCase();
        return lower.matches(".*\\.(txt|doc|docx|pdf|jpg|jpeg|png|gif|mp4|avi|mov|mp3|wav|zip|rar|xlsx|xls|ppt|pptx)$");
    }

    private boolean duplicate(String agentId, Map<String, Object> payload) {
        String id = ChannelHttpSupport.first(payload, "msg_id", "message_id", "id", "context_token");
        if (id.isBlank()) return false;
        Map<String, Long> ids = processed.computeIfAbsent(key(agentId), ignored -> new ConcurrentHashMap<>());
        Long previous = ids.putIfAbsent(id, System.currentTimeMillis());
        if (ids.size() > PROCESSED_LIMIT) {
            long cutoff = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
            ids.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            if (ids.size() > PROCESSED_LIMIT) {
                ids.clear();
                ids.put(id, System.currentTimeMillis());
            }
        }
        return previous != null;
    }

    private int intValue(Object raw) {
        if (raw instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            return 0;
        }
    }

    private String base(Map<String, Object> config) {
        String base = stringValue(config.get("base_url"));
        return ChannelHttpSupport.trimSlash(base.isBlank() ? DEFAULT_BASE : base);
    }

    private boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    @Override
    public void close() {
        active.clear();
        tasks.values().forEach(task -> task.cancel(true));
        typingTasks.values().forEach(task -> task.cancel(true));
    }

    private enum PollOutcome {
        CONTINUE,
        RETRY,
        STOP
    }
}

class QQChannelAdapter extends BasicChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(QQChannelAdapter.class);
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final int INTENTS = (1 << 30) | (1 << 12) | (1 << 25) | (1 << 1) | (1 << 26);
    private static final int TEXT_CHUNK_SIZE = 1800;

    private final Executor worker;
    private final ScheduledExecutorService executor;
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> reconnectTasks = new ConcurrentHashMap<>();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, Integer> seq = new ConcurrentHashMap<>();
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Set<String> desired = ConcurrentHashMap.newKeySet();
    private final Set<String> ready = ConcurrentHashMap.newKeySet();

    QQChannelAdapter(Executor worker, ScheduledExecutorService executor) {
        super("qq", true, true, true, false);
        this.worker = worker;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        Map<String, Object> runtime = new LinkedHashMap<>(config != null ? config : Map.of());
        List<String> missing = ChannelHttpSupport.missing(runtime, "app_id", "client_secret");
        if (!Boolean.TRUE.equals(runtime.get("enabled")) || !missing.isEmpty()) {
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        String key = key(agentId);
        desired.add(key);
        ready.remove(key);
        lastErrors.remove(key);
        reconnectAttempts.remove(key);
        cancelReconnect(key);
        tokens.remove(stringValue(runtime.get("app_id")));
        return CompletableFuture.supplyAsync(() -> {
            try {
                connect(agentId, runtime, dispatcher);
                super.start(agentId, runtime);
                return health(agentId, runtime);
            } catch (Exception e) {
                recordError(key, "QQ connection failed", e);
                scheduleReconnect(agentId, runtime, dispatcher);
                throw new CompletionException(new IllegalStateException(
                        "QQ channel connection failed: " + errorMessage(e), e));
            }
        }, worker);
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        String key = key(agentId);
        desired.remove(key);
        ready.remove(key);
        reconnectAttempts.remove(key);
        lastErrors.remove(key);
        cancelReconnect(key);
        WebSocket ws = sockets.remove(key);
        if (ws != null) ws.abort();
        ScheduledFuture<?> hb = heartbeats.remove(key);
        if (hb != null) hb.cancel(true);
        return super.stop(agentId);
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        try {
            String key = Base64.getEncoder().encodeToString(randomBytes(32));
            String host = portalHost(config);
            HttpResponse<String> response = ChannelHttpSupport.post("https://" + host + "/lite/create_bind_task",
                    Map.of(), Map.of("key", key));
            if (!ok(response)) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false,
                        "status", "failed", "detail", "QQ QR code HTTP " + response.statusCode());
            }
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            if (!"0".equals(stringValue(body.get("retcode")))) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", response.body());
            }
            String taskId = stringValue(ChannelHttpSupport.map(body.get("data")).get("task_id"));
            if (taskId.isBlank()) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false,
                        "status", "failed", "detail", "QQ returned an empty authorization task id");
            }
            String scanUrl = "https://" + host + "/qqbot/openclaw/connect.html?task_id="
                    + ChannelHttpSupport.encode(taskId) + "&_wv=2&source=MelonPaw";
            String token = Base64.getUrlEncoder().encodeToString(JsonUtils.toJson(Map.of("task_id", taskId, "key", key))
                    .getBytes(StandardCharsets.UTF_8));
            return Map.of("qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl), "poll_token", token,
                    "enabled", true, "status", "waiting", "detail", "");
        } catch (Exception e) {
            return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        try {
            Map<String, Object> decoded = ChannelHttpSupport.json(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            String taskId = stringValue(decoded.get("task_id"));
            String key = stringValue(decoded.get("key"));
            HttpResponse<String> response = ChannelHttpSupport.post("https://" + portalHost(config) + "/lite/poll_bind_result",
                    Map.of(), Map.of("task_id", taskId));
            if (!ok(response)) {
                return Map.of("status", "fail", "credentials", Map.of(),
                        "detail", "QQ QR status HTTP " + response.statusCode());
            }
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            if (!"0".equals(stringValue(body.get("retcode")))) {
                return Map.of("status", "fail", "credentials", Map.of("fail_reason", body.getOrDefault("msg", "unknown")));
            }
            Map<?, ?> data = ChannelHttpSupport.map(body.get("data"));
            int status = intValue(data.get("status"));
            if (status == 2) {
                String secret = ChannelHttpSupport.aesGcmDecryptBase64(stringValue(data.get("bot_encrypt_secret")), key);
                return Map.of("status", "success", "credentials", Map.of(
                        "app_id", stringValue(data.get("bot_appid")),
                        "client_secret", secret,
                        "user_openid", stringValue(data.get("user_openid"))));
            }
            if (status == 3) return Map.of("status", "expired", "credentials", Map.of());
            return Map.of("status", "waiting", "credentials", Map.of());
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of("fail_reason", e.getMessage()));
        }
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) {
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        }
        List<String> missing = ChannelHttpSupport.missing(config, "app_id", "client_secret");
        if (!missing.isEmpty()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        }
        String key = key(agentId);
        boolean connected = sockets.containsKey(key);
        if (connected && ready.contains(key)) {
            return ChannelHealth.of(type(), "running", true, true, true,
                    "QQ gateway WebSocket is connected.");
        }
        if (connected) {
            return ChannelHealth.of(type(), "starting", false, true, true,
                    "QQ gateway WebSocket is connected and waiting for READY.");
        }
        String error = lastErrors.get(key);
        ScheduledFuture<?> reconnect = reconnectTasks.get(key);
        if (reconnect != null && !reconnect.isDone() && !reconnect.isCancelled()) {
            return ChannelHealth.of(type(), "reconnecting", false, false, true,
                    error != null ? error : "QQ channel is reconnecting.");
        }
        return ChannelHealth.of(type(), error == null ? "configured" : "unhealthy", false,
                error == null, true, error == null ? "QQ channel is configured." : error);
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        return inbound(agentId, body, headers);
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = token(config).value;
                Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
                String msgId = ChannelHttpSupport.first(meta, "message_id", "msg_id", "id");
                QQRoute route = resolveRoute(message, meta);
                boolean markdown = booleanValue(meta.get("markdown_enabled"), booleanValue(config.get("markdown_enabled"), true));
                boolean sent = false;
                String detail = "";
                for (String chunk : splitText(message.getText())) {
                    SendResult result = sendTextChunk(config, token, route, chunk, msgId, markdown);
                    if (!result.ok() && markdown && shouldPlainTextFallback(result.detail())) {
                        String fallbackText = ChannelHttpSupport.sanitizeQqText(chunk);
                        result = sendTextChunk(config, token, route, fallbackText, msgId, false);
                    } else if (!result.ok() && !markdown && isUrlContentError(result.detail())) {
                        result = sendTextChunk(config, token, route, aggressiveSanitizeQqText(chunk), msgId, false);
                    }
                    sent = result.ok();
                    detail = result.detail();
                    if (!sent) break;
                }
                ChannelHttpSupport.mark(message, sent ? "sent" : "failed", detail);
                if (sent) {
                    log.info("QQ outbound sent: agent={}, type={}, path={}, session={}, text_len={}",
                            message.getAgentId(), route.messageType(), route.path(), message.getSessionId(), message.getText().length());
                } else {
                    log.warn("QQ outbound failed: agent={}, type={}, path={}, session={}, detail={}",
                            message.getAgentId(), route.messageType(), route.path(), message.getSessionId(), detail);
                }
            } catch (Exception e) {
                ChannelHttpSupport.mark(message, "failed", e.getMessage());
                log.warn("QQ send exception: agent={}, session={}, error={}",
                        message.getAgentId(), message.getSessionId(), e.toString());
            }
            return message;
        }, worker);
    }

    private QQRoute resolveRoute(ChannelOutboundMessage message, Map<?, ?> meta) {
        Map<?, ?> extra = message.getTo() != null ? message.getTo().getExtra() : Map.of();
        String messageType = firstNonBlank(
                ChannelHttpSupport.first(meta, "message_type"),
                ChannelHttpSupport.first(extra, "message_type"),
                message.getTo() != null ? stringValue(message.getTo().getKind()) : "");
        String toHandle = message.getTo() != null ? message.getTo().toHandle() : "";
        String toId = message.getTo() != null ? stringValue(message.getTo().getId()) : "";
        String senderId = firstNonBlank(
                ChannelHttpSupport.first(meta, "sender_id", "user_openid"),
                ChannelHttpSupport.first(extra, "sender_id", "user_openid"),
                handleId(toHandle, "c2c:"),
                plainQqTarget(toId),
                stringValue(message.getUserId()));
        String channelId = firstNonBlank(
                ChannelHttpSupport.first(meta, "channel_id"),
                ChannelHttpSupport.first(extra, "channel_id"),
                handleId(toHandle, "channel:"),
                "guild".equals(messageType) ? plainQqTarget(toId) : "");
        String groupOpenid = firstNonBlank(
                ChannelHttpSupport.first(meta, "group_openid"),
                ChannelHttpSupport.first(extra, "group_openid"),
                handleId(toHandle, "group:"),
                "group".equals(messageType) ? plainQqTarget(toId) : "");
        String guildId = firstNonBlank(
                ChannelHttpSupport.first(meta, "guild_id"),
                ChannelHttpSupport.first(extra, "guild_id"),
                handleId(toHandle, "dm:"),
                "dm".equals(messageType) ? plainQqTarget(toId) : "");
        if (messageType.isBlank()) {
            if (toHandle.startsWith("group:") || !groupOpenid.isBlank()) {
                messageType = "group";
            } else if (toHandle.startsWith("channel:") || !channelId.isBlank()) {
                messageType = "guild";
            } else if (toHandle.startsWith("dm:") || !guildId.isBlank()) {
                messageType = "dm";
            } else {
                messageType = "c2c";
            }
        }
        if ("group".equals(messageType) && !groupOpenid.isBlank()) {
            return new QQRoute(messageType, "/v2/groups/" + ChannelHttpSupport.encode(groupOpenid) + "/messages", true, "group");
        }
        if ("guild".equals(messageType) && !channelId.isBlank()) {
            return new QQRoute(messageType, "/channels/" + ChannelHttpSupport.encode(channelId) + "/messages", false, "");
        }
        if ("dm".equals(messageType) && !guildId.isBlank()) {
            return new QQRoute(messageType, "/dms/" + ChannelHttpSupport.encode(guildId) + "/messages", false, "");
        }
        if (senderId.isBlank()) {
            throw new IllegalStateException("QQ outbound target is empty: type=" + messageType + ", session=" + message.getSessionId());
        }
        return new QQRoute("c2c", "/v2/users/" + ChannelHttpSupport.encode(senderId) + "/messages", true, "c2c");
    }

    private SendResult sendTextChunk(Map<String, Object> config,
                                     String token,
                                     QQRoute route,
                                     String text,
                                     String msgId,
                                     boolean markdown) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (markdown) {
            body.put("markdown", Map.of("content", text));
            if (route.useSeq()) body.put("msg_type", 2);
        } else {
            body.put("content", text);
            if (route.useSeq()) body.put("msg_type", 0);
        }
        if (route.useSeq()) {
            body.put("msg_seq", nextMsgSeq(msgId.isBlank() ? route.seqKey() : msgId));
        }
        if (!msgId.isBlank()) body.put("msg_id", msgId);
        HttpResponse<String> response = ChannelHttpSupport.post(apiBase(config) + route.path(),
                Map.of("Authorization", "QQBot " + token), body);
        String detail = response.statusCode() + " " + response.body();
        return new SendResult(qqAccepted(response), detail);
    }

    private int nextMsgSeq(String key) {
        String seqKey = key == null || key.isBlank() ? "default" : key;
        return seq.merge(seqKey, 1, Integer::sum);
    }

    private boolean qqAccepted(HttpResponse<String> response) {
        if (!ok(response)) return false;
        Map<String, Object> body = ChannelHttpSupport.json(response.body());
        if (body.isEmpty()) return true;
        for (String key : List.of("code", "errcode", "ret", "retcode", "error_code")) {
            Object raw = body.get(key);
            if (raw == null) continue;
            String value = stringValue(raw);
            if (!value.isBlank() && !"0".equals(value)) return false;
        }
        return true;
    }

    private List<String> splitText(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < value.length(); start += TEXT_CHUNK_SIZE) {
            int end = Math.min(value.length(), start + TEXT_CHUNK_SIZE);
            chunks.add(value.substring(start, end));
        }
        return chunks;
    }

    private boolean shouldPlainTextFallback(String detail) {
        String text = detail != null ? detail.toLowerCase() : "";
        return text.contains("markdown") || text.contains("50056") || text.contains("40034012")
                || text.contains("不允许发送原生 markdown");
    }

    private boolean isUrlContentError(String detail) {
        String text = detail != null ? detail.toLowerCase() : "";
        return text.contains("url") || text.contains("链接") || text.contains("domain");
    }

    private String aggressiveSanitizeQqText(String text) {
        return ChannelHttpSupport.sanitizeQqText(text).replaceAll("\\b[\\w][\\w.-]*\\.(com|cn|org|net|edu|gov|io|co|cc|tv|me|info|biz|app|dev|top|xyz)(/\\S*)?", "[链接已省略]");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String handleId(String value, String prefix) {
        String text = stringValue(value);
        return text.startsWith(prefix) ? text.substring(prefix.length()) : "";
    }

    private String plainQqTarget(String value) {
        String text = stringValue(value);
        if (text.isBlank() || "default".equals(text)) return "";
        if (text.startsWith("qq:group:") || text.startsWith("qq:channel:")) return "";
        if (text.startsWith("qq:")) return text.substring(3);
        if (text.startsWith("group:") || text.startsWith("channel:") || text.startsWith("dm:") || text.startsWith("c2c:")) return "";
        return text;
    }

    private void connect(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) throws Exception {
        String key = key(agentId);
        if (!desired.contains(key) || sockets.containsKey(key)) return;
        String token = token(config).value;
        HttpResponse<String> gateway = ChannelHttpSupport.get(apiBase(config) + "/gateway",
                Map.of("Authorization", "QQBot " + token, "Content-Type", "application/json"));
        if (gateway.statusCode() < 200 || gateway.statusCode() >= 300) {
            throw new IllegalStateException("QQ gateway HTTP " + gateway.statusCode() + ": " + gateway.body());
        }
        String url = stringValue(ChannelHttpSupport.json(gateway.body()).get("url"));
        if (url.isBlank()) throw new IllegalStateException("QQ gateway url is empty: " + gateway.body());
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(OutboundNetworkPolicy.requirePublicWebUri(url),
                        new QQListener(agentId, config, dispatcher, token))
                .join();
        sockets.put(key, ws);
        lastErrors.remove(key);
        log.info("QQ gateway WebSocket connected: agent={}", agentId);
    }

    private void handleDisconnect(String agentId, WebSocket webSocket,
                                  Map<String, Object> config,
                                  ChannelInboundDispatcher dispatcher,
                                  String reason, Throwable error) {
        String key = key(agentId);
        if (!sockets.remove(key, webSocket)) return;
        ready.remove(key);
        ScheduledFuture<?> heartbeat = heartbeats.remove(key);
        if (heartbeat != null) heartbeat.cancel(true);
        if (error != null) {
            recordError(key, reason, error);
        } else {
            lastErrors.put(key, reason);
            log.warn("{}: agent={}", reason, agentId);
        }
        if (desired.contains(key)) scheduleReconnect(agentId, config, dispatcher);
    }

    private void scheduleReconnect(String agentId, Map<String, Object> config,
                                   ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        if (!desired.contains(key)) return;
        reconnectTasks.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isDone() && !existing.isCancelled()) return existing;
            int attempt = reconnectAttempts.merge(key, 1, Integer::sum);
            int maxAttempts = intValue(config.get("max_reconnect_attempts"));
            if (maxAttempts == 0) maxAttempts = 100;
            if (maxAttempts != -1 && attempt > maxAttempts) {
                lastErrors.put(key, "QQ maximum reconnect attempts reached: " + maxAttempts);
                return null;
            }
            long delay = Math.min(30L, 1L << Math.min(Math.max(attempt - 1, 0), 5));
            log.info("QQ reconnect scheduled: agent={}, attempt={}, delay={}s", agentId, attempt, delay);
            return executor.schedule(() -> {
                reconnectTasks.remove(key);
                if (!desired.contains(key)) return;
                try {
                    connect(agentId, config, dispatcher);
                    super.start(agentId, config);
                } catch (Exception e) {
                    recordError(key, "QQ reconnect failed", e);
                    scheduleReconnect(agentId, config, dispatcher);
                }
            }, delay, TimeUnit.SECONDS);
        });
    }

    private void cancelReconnect(String key) {
        ScheduledFuture<?> reconnect = reconnectTasks.remove(key);
        if (reconnect != null) reconnect.cancel(true);
    }

    private void recordError(String key, String prefix, Throwable error) {
        String detail = prefix + ": " + errorMessage(error);
        lastErrors.put(key, detail);
        log.warn(detail);
    }

    private String errorMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return stringValue(current.getMessage(), current.getClass().getSimpleName());
    }

    private ChannelInboundMessage inbound(String agentId, Map<String, Object> payload, Map<String, String> headers) {
        Map<?, ?> data = payload.containsKey("d") ? ChannelHttpSupport.map(payload.get("d")) : payload;
        String event = stringValue(payload.get("t"), stringValue(payload.get("event_type")));
        Map<?, ?> author = ChannelHttpSupport.map(data.get("author"));
        Map<?, ?> member = ChannelHttpSupport.map(data.get("member"));
        String messageType = switch (event) {
            case "GROUP_AT_MESSAGE_CREATE" -> "group";
            case "AT_MESSAGE_CREATE" -> "guild";
            case "DIRECT_MESSAGE_CREATE" -> "dm";
            default -> "c2c";
        };
        String sender = switch (messageType) {
            case "group" -> firstNonBlank(
                    ChannelHttpSupport.first(author, "member_openid", "id"),
                    ChannelHttpSupport.first(data, "member_openid", "user_openid", "id"),
                    ChannelHttpSupport.first(member, "member_openid", "user_id"));
            case "guild", "dm" -> firstNonBlank(
                    ChannelHttpSupport.first(author, "id", "username"),
                    ChannelHttpSupport.first(data, "user_openid", "id"));
            default -> firstNonBlank(
                    ChannelHttpSupport.first(author, "user_openid", "id"),
                    ChannelHttpSupport.first(data, "user_openid", "id"));
        };
        String group = firstNonBlank(ChannelHttpSupport.first(data, "group_openid"), ChannelHttpSupport.first(author, "group_openid"));
        String channel = firstNonBlank(ChannelHttpSupport.first(data, "channel_id"), ChannelHttpSupport.first(author, "channel_id"));
        String guild = firstNonBlank(ChannelHttpSupport.first(data, "guild_id"), ChannelHttpSupport.first(author, "guild_id"));
        String msgId = ChannelHttpSupport.first(data, "id", "message_id", "msg_id");
        String session = "group".equals(messageType) && !group.isBlank() ? "qq:group:" + group
                : "guild".equals(messageType) && !channel.isBlank() ? "qq:channel:" + channel
                : "dm".equals(messageType) && !guild.isBlank() ? "qq:dm:" + guild
                : "qq:" + sender;
        Map<String, Object> meta = ChannelHttpSupport.copy(data);
        meta.put("message_type", messageType);
        meta.put("message_id", msgId);
        meta.put("sender_id", sender);
        meta.put("user_name", ChannelHttpSupport.first(author, "username", "nick", "nickname"));
        meta.put("is_group", "group".equals(messageType) || "guild".equals(messageType));
        meta.put("incoming_raw", ChannelHttpSupport.copy(data));
        meta.put("attachments", ChannelHttpSupport.list(data.get("attachments")));
        if (!group.isBlank()) meta.put("group_openid", group);
        if (!channel.isBlank()) meta.put("channel_id", channel);
        if (!guild.isBlank()) meta.put("guild_id", guild);
        String replyId = switch (messageType) {
            case "group" -> group;
            case "guild" -> channel;
            case "dm" -> guild;
            default -> sender;
        };
        String toHandle = switch (messageType) {
            case "group" -> "group:" + group;
            case "guild" -> "channel:" + channel;
            case "dm" -> "dm:" + guild;
            default -> "c2c:" + sender;
        };
        Map<String, Object> replyExtra = new LinkedHashMap<>();
        replyExtra.put("sender_id", sender);
        replyExtra.put("group_openid", group);
        replyExtra.put("channel_id", channel);
        replyExtra.put("guild_id", guild);
        replyExtra.put("message_type", messageType);
        replyExtra.put("message_id", msgId);
        replyExtra.put("to_handle", toHandle);
        replyExtra.put("headers", headers != null ? headers : Map.of());
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(sender);
        inbound.setSessionId(session);
        inbound.setContent(ChannelHttpSupport.first(data, "content", "text", "message"));
        inbound.setChannelMeta(meta);
        inbound.setAttachments(qqAttachments(data));
        inbound.setReplyTo(new ChannelAddress(messageType, replyId, replyExtra));
        return inbound;
    }

    private List<Map<String, Object>> qqAttachments(Map<?, ?> data) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : ChannelHttpSupport.list(data.get("attachments"))) {
            Map<?, ?> raw = ChannelHttpSupport.map(item);
            if (raw.isEmpty()) continue;
            Map<String, Object> attachment = new LinkedHashMap<>();
            String url = ChannelHttpSupport.first(raw, "url", "download_url", "file_url");
            String name = ChannelHttpSupport.first(raw, "filename", "file_name", "name", "id");
            attachment.put("type", "data");
            attachment.put("name", name.isBlank() ? "qq-attachment" : name);
            attachment.put("filename", name.isBlank() ? "qq-attachment" : name);
            if (!url.isBlank()) {
                attachment.put("url", url);
                attachment.put("source", Map.of("type", "url", "url", url, "media_type", ChannelHttpSupport.first(raw, "content_type", "media_type")));
            }
            result.add(attachment);
        }
        return result;
    }

    private Token token(Map<String, Object> config) throws Exception {
        String cacheKey = stringValue(config.get("app_id"));
        Token cached = tokens.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt - 300_000L) return cached;
        HttpResponse<String> response = ChannelHttpSupport.post(TOKEN_URL, Map.of(),
                Map.of("appId", config.get("app_id"), "clientSecret", config.get("client_secret")));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("QQ token HTTP " + response.statusCode() + ": " + response.body());
        }
        Map<String, Object> body = ChannelHttpSupport.json(response.body());
        String value = stringValue(body.get("access_token"));
        if (value.isBlank()) throw new IllegalStateException("QQ access_token is empty: " + response.body());
        long expires = longValue(body.get("expires_in"), 7200) * 1000L;
        Token token = new Token(value, System.currentTimeMillis() + expires);
        tokens.put(cacheKey, token);
        return token;
    }

    private String apiBase(Map<String, Object> config) {
        String base = stringValue(config.get("api_base"));
        return ChannelHttpSupport.trimSlash(base.isBlank() ? "https://api.sgroup.qq.com" : base);
    }

    private boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private long longValue(Object raw, long fallback) {
        if (raw instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (Exception e) {
            return fallback;
        }
    }

    private int intValue(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            return 0;
        }
    }

    private String portalHost(Map<String, Object> config) {
        String host = stringValue(config.get("portal_host"), stringValue(System.getenv("QQ_PORTAL_HOST"), "q.qq.com"));
        return host.replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private byte[] randomBytes(int len) {
        byte[] bytes = new byte[len];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private record Token(String value, long expiresAt) {
    }

    private record QQRoute(String messageType, String path, boolean useSeq, String seqKey) {
    }

    private record SendResult(boolean ok, String detail) {
    }

    private class QQListener implements WebSocket.Listener {
        private final String agentId;
        private final Map<String, Object> config;
        private final ChannelInboundDispatcher dispatcher;
        private final String token;
        private final StringBuilder buffer = new StringBuilder();
        private volatile long lastSeq = 0;

        QQListener(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher, String token) {
            this.agentId = agentId;
            this.config = config;
            this.dispatcher = dispatcher;
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!desired.contains(key(agentId))) {
                webSocket.abort();
                return;
            }
            sockets.put(key(agentId), webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (sockets.get(key(agentId)) != webSocket) {
                webSocket.request(1);
                return null;
            }
            buffer.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            Map<String, Object> payload = ChannelHttpSupport.json(buffer.toString());
            buffer.setLength(0);
            int op = intValue(payload.get("op"));
            if (payload.get("s") instanceof Number n) lastSeq = n.longValue();
            if (op == 10) {
                Map<?, ?> d = ChannelHttpSupport.map(payload.get("d"));
                int interval = intValue(d.get("heartbeat_interval"));
                identify(webSocket);
                scheduleHeartbeat(agentId, webSocket, interval > 0 ? interval : 30_000);
            } else if (op == 0) {
                String event = stringValue(payload.get("t"));
                if ("READY".equals(event) || "RESUMED".equals(event)) {
                    String key = key(agentId);
                    ready.add(key);
                    reconnectAttempts.remove(key);
                    lastErrors.remove(key);
                    log.info("QQ gateway ready: agent={}, event={}", agentId, event);
                }
                if (isMessageEvent(event)) {
                    ChannelInboundMessage inbound = inbound(agentId, payload, Map.of());
                    if (!inbound.getUserId().isBlank()
                            && (!inbound.getContent().isBlank()
                            || (inbound.getAttachments() != null && !inbound.getAttachments().isEmpty()))) {
                        dispatcher.dispatch(inbound, 20);
                    }
                }
            } else if (op == 7 || op == 9) {
                handleDisconnect(agentId, webSocket, config, dispatcher,
                        "QQ gateway requested reconnect (op=" + op + ")", null);
                webSocket.abort();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleDisconnect(agentId, webSocket, config, dispatcher,
                    "QQ gateway closed: " + statusCode + " " + stringValue(reason), null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnect(agentId, webSocket, config, dispatcher,
                    "QQ gateway WebSocket error", error);
        }

        private boolean isMessageEvent(String event) {
            return "C2C_MESSAGE_CREATE".equals(event)
                    || "GROUP_AT_MESSAGE_CREATE".equals(event)
                    || "AT_MESSAGE_CREATE".equals(event)
                    || "DIRECT_MESSAGE_CREATE".equals(event);
        }

        private void identify(WebSocket webSocket) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("op", 2);
            body.put("d", Map.of(
                    "token", "QQBot " + token,
                    "intents", INTENTS,
                    "shard", List.of(0, 1),
                    "properties", Map.of()));
            webSocket.sendText(JsonUtils.toJson(body), true);
        }

        private void scheduleHeartbeat(String agentId, WebSocket webSocket, int intervalMs) {
            ScheduledFuture<?> old = heartbeats.remove(key(agentId));
            if (old != null) old.cancel(true);
            heartbeats.put(key(agentId), executor.scheduleAtFixedRate(() -> {
                Map<String, Object> body = Map.of("op", 1, "d", lastSeq);
                webSocket.sendText(JsonUtils.toJson(body), true);
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS));
        }

        private int intValue(Object raw) {
            if (raw instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(raw));
            } catch (Exception e) {
                return 0;
            }
        }
    }

    @Override
    public void close() {
        desired.clear();
        reconnectTasks.values().forEach(task -> task.cancel(true));
        sockets.values().forEach(WebSocket::abort);
        heartbeats.values().forEach(task -> task.cancel(true));
    }
}

class WeComChannelAdapter extends BasicChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(WeComChannelAdapter.class);
    private static final String WECOM_AUTH_ORIGIN = "https://work.weixin.qq.com";
    private static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";
    private static final String SUBSCRIBE = "aibot_subscribe";
    private static final String HEARTBEAT = "ping";
    private static final String CALLBACK = "aibot_msg_callback";
    private static final String EVENT_CALLBACK = "aibot_event_callback";
    private static final String RESPOND = "aibot_respond_msg";
    private static final String RESPOND_WELCOME = "aibot_respond_welcome_msg";
    private static final String SEND = "aibot_send_msg";
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long HEARTBEAT_TIMEOUT_MS = 75_000L;
    private static final int PROCESSED_LIMIT = 2_000;

    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> reconnectTasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPongs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> processed = new ConcurrentHashMap<>();
    private final Map<String, PendingAck> pendingAcks = new ConcurrentHashMap<>();
    private final Set<String> desired = ConcurrentHashMap.newKeySet();
    private final Set<String> ready = ConcurrentHashMap.newKeySet();
    private final Executor worker;
    private final ScheduledExecutorService executor;

    WeComChannelAdapter(Executor worker, ScheduledExecutorService executor) {
        super("wecom", true, true, true, false);
        this.worker = worker;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId, Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
        Map<String, Object> runtime = new LinkedHashMap<>(config != null ? config : Map.of());
        if (!Boolean.TRUE.equals(runtime.get("enabled"))
                || !ChannelHttpSupport.missing(runtime, "bot_id", "secret").isEmpty()) {
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        String key = key(agentId);
        if (sockets.containsKey(key)) {
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        desired.add(key);
        ready.remove(key);
        lastErrors.remove(key);
        reconnectAttempts.remove(key);
        cancelReconnect(key);
        String generation = UUID.randomUUID().toString();
        active.put(key, generation);
        return connect(agentId, generation, runtime, dispatcher).thenApply(ignored -> {
            super.start(agentId, runtime);
            return health(agentId, runtime);
        });
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        String key = key(agentId);
        desired.remove(key);
        ready.remove(key);
        active.remove(key);
        reconnectAttempts.remove(key);
        lastErrors.remove(key);
        lastPongs.remove(key);
        cancelReconnect(key);
        cancelHeartbeat(key);
        WebSocket ws = sockets.remove(key);
        if (ws != null) ws.abort();
        pendingAcks.entrySet().removeIf(entry -> {
            if (!entry.getValue().agentKey().equals(key)) return false;
            entry.getValue().future().completeExceptionally(
                    new IllegalStateException("WeCom channel stopped"));
            return true;
        });
        return super.stop(agentId);
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        try {
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(ChannelHttpSupport.randomHex(12).getBytes(StandardCharsets.UTF_8));
            String url = WECOM_AUTH_ORIGIN + "/ai/qc/gen?source=melonpaw&state=" + ChannelHttpSupport.encode(state)
                    + "&timestamp=" + System.currentTimeMillis();
            HttpResponse<String> response = ChannelHttpSupport.get(url, Map.of());
            if (!ok(response)) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false,
                        "status", "failed", "detail", "WeCom QR code HTTP " + response.statusCode());
            }
            java.util.regex.Matcher matcher = Pattern.compile("window\\.settings\\s*=\\s*(\\{[^<]+\\})").matcher(response.body());
            if (!matcher.find()) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", "Failed to parse WeCom auth page settings");
            }
            Map<String, Object> settings = ChannelHttpSupport.json(matcher.group(1));
            String scode = stringValue(settings.get("scode"));
            String authUrl = stringValue(settings.get("auth_url"));
            if (scode.isBlank() || authUrl.isBlank()) {
                return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", "WeCom returned empty scode or auth_url");
            }
            return Map.of("qrcode_img", ChannelHttpSupport.qrPngBase64(authUrl), "poll_token", scode,
                    "enabled", true, "status", "waiting", "detail", "");
        } catch (Exception e) {
            return Map.of("qrcode_img", "", "poll_token", "", "enabled", false, "status", "failed", "detail", stringValue(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        try {
            String url = WECOM_AUTH_ORIGIN + "/ai/qc/query_result?scode=" + ChannelHttpSupport.encode(token);
            HttpResponse<String> response = ChannelHttpSupport.get(url, Map.of());
            if (!ok(response)) {
                return Map.of("status", "failed", "credentials", Map.of(),
                        "detail", "WeCom QR status HTTP " + response.statusCode());
            }
            Map<String, Object> body = ChannelHttpSupport.json(response.body());
            Map<?, ?> data = ChannelHttpSupport.map(body.get("data"));
            Map<?, ?> botInfo = ChannelHttpSupport.map(data.get("bot_info"));
            return Map.of("status", stringValue(data.get("status"), "waiting"), "credentials", Map.of(
                    "bot_id", stringValue(botInfo.get("botid")),
                    "secret", stringValue(botInfo.get("secret"))));
        } catch (Exception e) {
            return Map.of("status", "failed", "credentials", Map.of(), "detail", stringValue(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        List<String> missing = ChannelHttpSupport.missing(config, "bot_id", "secret");
        if (!missing.isEmpty()) return ChannelHealth.of(type(), "misconfigured", false, false, true, "Missing required config: " + String.join(", ", missing));
        String key = key(agentId);
        if (ready.contains(key) && sockets.containsKey(key)) {
            return ChannelHealth.of(type(), "running", true, true, true,
                    "WeCom WebSocket is authenticated and connected.");
        }
        if (sockets.containsKey(key)) {
            return ChannelHealth.of(type(), "starting", false, true, true,
                    "WeCom WebSocket is connected and waiting for authentication.");
        }
        String error = lastErrors.get(key);
        ScheduledFuture<?> reconnect = reconnectTasks.get(key);
        if (reconnect != null && !reconnect.isCancelled() && !reconnect.isDone()) {
            return ChannelHealth.of(type(), "reconnecting", false, false, true,
                    error != null ? error : "WeCom WebSocket is reconnecting.");
        }
        return ChannelHealth.of(type(), error == null ? "configured" : "unhealthy", false,
                error == null, true, error == null
                        ? "WeCom is configured but not connected."
                        : error);
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body, Map<String, String> headers) {
        Map<String, Object> payload = new LinkedHashMap<>(body != null ? body : Map.of());
        Map<?, ?> from = ChannelHttpSupport.map(payload.get("from"));
        String sender = ChannelHttpSupport.first(payload, "sender_id", "userid", "user_id", "from_user_id");
        if (sender.isBlank()) sender = ChannelHttpSupport.first(from, "userid", "user_id", "openid");
        String chatid = ChannelHttpSupport.first(body, "chatid", "chat_id", "conversation_id");
        String chatType = ChannelHttpSupport.first(payload, "chattype", "chat_type");
        if (chatType.isBlank()) chatType = "single";
        String msgType = ChannelHttpSupport.first(payload, "msgtype", "message_type");
        String text = ChannelHttpSupport.first(payload, "content", "message");
        Map<?, ?> textMap = ChannelHttpSupport.map(payload.get("text"));
        if (text.isBlank()) text = stringValue(textMap.get("content"));
        if (text.isBlank() && "voice".equals(msgType)) {
            text = stringValue(ChannelHttpSupport.map(payload.get("voice")).get("content"));
        }
        List<Map<String, Object>> attachments = new ArrayList<>();
        if ("mixed".equals(msgType)) {
            StringBuilder mixedText = new StringBuilder(text);
            for (Object raw : ChannelHttpSupport.list(ChannelHttpSupport.map(payload.get("mixed")).get("msg_item"))) {
                Map<?, ?> item = ChannelHttpSupport.map(raw);
                String itemType = ChannelHttpSupport.first(item, "msgtype");
                if ("text".equals(itemType)) {
                    String value = stringValue(ChannelHttpSupport.map(item.get("text")).get("content"));
                    if (!value.isBlank()) {
                        if (!mixedText.isEmpty()) mixedText.append('\n');
                        mixedText.append(value);
                    }
                } else {
                    Map<String, Object> attachment = wecomAttachment(itemType, item.get(itemType));
                    if (!attachment.isEmpty()) attachments.add(attachment);
                }
            }
            text = mixedText.toString();
        } else {
            Map<String, Object> attachment = wecomAttachment(msgType, payload.get(msgType));
            if (!attachment.isEmpty()) attachments.add(attachment);
        }
        String session = "group".equals(chatType) && !chatid.isBlank() ? "wecom:group:" + chatid : "wecom:" + sender;
        String reqId = headers != null ? stringValue(headers.get("wecom_req_id")) : "";
        payload.put("wecom_sender_id", sender);
        payload.put("wecom_chatid", chatid);
        payload.put("wecom_chat_type", chatType);
        payload.put("wecom_req_id", reqId);
        payload.put("is_group", "group".equals(chatType));
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(sender);
        inbound.setSessionId(session);
        inbound.setContent(text);
        inbound.setAttachments(attachments);
        inbound.setChannelMeta(payload);
        String target = !chatid.isBlank() ? chatid : sender;
        inbound.setReplyTo(new ChannelAddress("group".equals(chatType) ? "group" : "dm", target, Map.of(
                "wecom_sender_id", sender,
                "wecom_chatid", chatid,
                "wecom_chat_type", chatType,
                "wecom_req_id", reqId,
                "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message, Map<String, Object> config) {
        String key = key(message.getAgentId());
        WebSocket socket = sockets.get(key);
        if (socket == null || !ready.contains(key)) {
            ChannelHttpSupport.mark(message, "failed", "WeCom WebSocket is not authenticated");
            return CompletableFuture.completedFuture(message);
        }
        Map<?, ?> meta = ChannelHttpSupport.map(message.getMeta().get("channel_meta"));
        Map<?, ?> extra = message.getTo() != null ? message.getTo().getExtra() : Map.of();
        String callbackReqId = ChannelHttpSupport.first(meta, "wecom_req_id");
        if (callbackReqId.isBlank()) callbackReqId = ChannelHttpSupport.first(extra, "wecom_req_id");
        String command;
        String reqId;
        Map<String, Object> body;
        if (!callbackReqId.isBlank()) {
            command = RESPOND;
            reqId = callbackReqId;
            body = Map.of("msgtype", "stream", "stream", Map.of(
                    "id", "melonpaw_" + UUID.randomUUID().toString().replace("-", ""),
                    "finish", true,
                    "content", message.getText()));
        } else {
            command = SEND;
            reqId = requestId(SEND);
            String target = ChannelHttpSupport.target(message, "wecom_chatid", "chatid", "wecom_sender_id");
            body = Map.of("chatid", target, "msgtype", "markdown",
                    "markdown", Map.of("content", message.getText()));
        }
        return sendFrame(key, socket, command, reqId, body)
                .handleAsync((ack, error) -> {
                    if (error != null) {
                        ChannelHttpSupport.mark(message, "failed", errorMessage(error));
                    } else {
                        int errcode = intValue(ack.get("errcode"));
                        ChannelHttpSupport.mark(message, errcode == 0 ? "sent" : "failed",
                                errcode == 0 ? "WeCom accepted the message"
                                        : errcode + " " + stringValue(ack.get("errmsg")));
                    }
                    return message;
                }, worker);
    }

    private CompletableFuture<Void> connect(String agentId, String generation,
                                            Map<String, Object> config,
                                            ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        String configuredUrl = stringValue(config.get("ws_url"));
        String wsUrl = configuredUrl.isBlank() ? DEFAULT_WS_URL : configuredUrl;
        WeComListener listener = new WeComListener(agentId, key, generation, config, dispatcher);
        CompletableFuture<Void> result = new CompletableFuture<>();
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(OutboundNetworkPolicy.requirePublicWebUri(wsUrl), listener)
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        recordError(key, "WeCom connection failed", error);
                        scheduleReconnect(agentId, generation, config, dispatcher);
                        result.completeExceptionally(new IllegalStateException(
                                "WeCom WebSocket connection failed: " + errorMessage(error), error));
                        return;
                    }
                    if (!isActive(key, generation)) {
                        socket.abort();
                        result.completeExceptionally(new IllegalStateException("WeCom start was superseded"));
                        return;
                    }
                    result.complete(null);
                });
        return result;
    }

    private CompletableFuture<Map<String, Object>> sendFrame(String agentKey, WebSocket socket,
                                                              String command, String reqId,
                                                              Map<String, Object> body) {
        CompletableFuture<Map<String, Object>> ack = new CompletableFuture<>();
        PendingAck pending = new PendingAck(agentKey, ack);
        PendingAck previous = pendingAcks.putIfAbsent(reqId, pending);
        if (previous != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A WeCom reply is already pending for request " + reqId));
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("cmd", command);
        frame.put("headers", Map.of("req_id", reqId));
        if (body != null && !body.isEmpty()) frame.put("body", body);
        socket.sendText(JsonUtils.toJson(frame), true).whenComplete((ignored, error) -> {
            if (error != null && pendingAcks.remove(reqId, pending)) {
                ack.completeExceptionally(error);
            }
        });
        return ack.orTimeout(6, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> pendingAcks.remove(reqId, pending));
    }

    private void scheduleHeartbeat(String agentId, String key, String generation,
                                   Map<String, Object> config,
                                   ChannelInboundDispatcher dispatcher,
                                   WebSocket socket) {
        cancelHeartbeat(key);
        lastPongs.put(key, System.currentTimeMillis());
        heartbeats.put(key, executor.scheduleAtFixedRate(() -> {
            if (!isActive(key, generation) || sockets.get(key) != socket) return;
            long lastPong = lastPongs.getOrDefault(key, 0L);
            if (System.currentTimeMillis() - lastPong > HEARTBEAT_TIMEOUT_MS) {
                disconnected(agentId, key, generation, config, dispatcher, socket,
                        "WeCom heartbeat timed out");
                socket.abort();
                return;
            }
            Map<String, Object> ping = Map.of(
                    "cmd", HEARTBEAT,
                    "headers", Map.of("req_id", requestId(HEARTBEAT)));
            socket.sendText(JsonUtils.toJson(ping), true).whenComplete((ignored, error) -> {
                if (error == null) return;
                disconnected(agentId, key, generation, config, dispatcher, socket,
                        "WeCom heartbeat send failed: " + errorMessage(error));
            });
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS));
    }

    private void scheduleReconnect(String agentId, String generation,
                                   Map<String, Object> config,
                                   ChannelInboundDispatcher dispatcher) {
        String key = key(agentId);
        if (!isActive(key, generation)) return;
        int attempt = reconnectAttempts.merge(key, 1, Integer::sum);
        int max = intValue(config.get("max_reconnect_attempts"));
        if (max == 0) max = -1;
        if (max >= 0 && attempt > max) {
            recordError(key, "WeCom maximum reconnect attempts reached", null);
            return;
        }
        long delay = Math.min(30L, 1L << Math.min(10, attempt - 1));
        reconnectTasks.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isDone() && !existing.isCancelled()) return existing;
            return executor.schedule(() -> {
                reconnectTasks.remove(key);
                if (!isActive(key, generation)) return;
                connect(agentId, generation, new LinkedHashMap<>(config), dispatcher)
                        .exceptionally(error -> null);
            }, delay, TimeUnit.SECONDS);
        });
    }

    private void disconnected(String agentId, String key, String generation,
                              Map<String, Object> config,
                              ChannelInboundDispatcher dispatcher,
                              WebSocket socket, String detail) {
        if (!isActive(key, generation) || !sockets.remove(key, socket)) return;
        ready.remove(key);
        cancelHeartbeat(key);
        recordError(key, detail, null);
        scheduleReconnect(agentId, generation, config, dispatcher);
    }

    private void cancelReconnect(String key) {
        ScheduledFuture<?> task = reconnectTasks.remove(key);
        if (task != null) task.cancel(true);
    }

    private void cancelHeartbeat(String key) {
        ScheduledFuture<?> task = heartbeats.remove(key);
        if (task != null) task.cancel(true);
    }

    private boolean isActive(String key, String generation) {
        return desired.contains(key) && generation.equals(active.get(key));
    }

    private void recordError(String key, String detail, Throwable error) {
        String message = error == null ? detail : detail + ": " + errorMessage(error);
        lastErrors.put(key, message);
        if (error == null) log.warn("{}: agent={}", message, key);
        else log.warn("{}: agent={}", message, key, error);
    }

    private boolean duplicate(String key, String messageId) {
        if (messageId.isBlank()) return false;
        Map<String, Long> ids = processed.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        if (ids.putIfAbsent(messageId, System.currentTimeMillis()) != null) return true;
        if (ids.size() > PROCESSED_LIMIT) {
            ids.entrySet().stream().min(Map.Entry.comparingByValue()).ifPresent(entry -> ids.remove(entry.getKey()));
        }
        return false;
    }

    private Map<String, Object> wecomAttachment(String type, Object raw) {
        if (!Set.of("image", "file", "video").contains(type)) return Map.of();
        Map<?, ?> value = ChannelHttpSupport.map(raw);
        String url = ChannelHttpSupport.first(value, "url");
        if (url.isBlank()) return Map.of();
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", type);
        attachment.put("url", url);
        attachment.put("aes_key", ChannelHttpSupport.first(value, "aeskey", "aes_key"));
        attachment.put("encryption", "aes-256-cbc");
        attachment.put("filename", stringValue(value.get("filename"), switch (type) {
            case "image" -> "wecom-image.jpg";
            case "video" -> "wecom-video.mp4";
            default -> "wecom-file";
        }));
        return attachment;
    }

    private String requestId(String command) {
        return command + "_" + System.currentTimeMillis() + "_" + ChannelHttpSupport.randomHex(4);
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private String errorMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return stringValue(cause.getMessage(), cause.getClass().getSimpleName());
    }

    @Override
    public void close() {
        desired.clear();
        active.clear();
        reconnectTasks.values().forEach(task -> task.cancel(true));
        heartbeats.values().forEach(task -> task.cancel(true));
        sockets.values().forEach(WebSocket::abort);
        pendingAcks.values().forEach(pending -> pending.future().completeExceptionally(
                new IllegalStateException("WeCom adapter closed")));
        pendingAcks.clear();
    }

    private record PendingAck(String agentKey, CompletableFuture<Map<String, Object>> future) {
    }

    private class WeComListener implements WebSocket.Listener {
        private final String agentId;
        private final String agentKey;
        private final String generation;
        private final Map<String, Object> config;
        private final ChannelInboundDispatcher dispatcher;
        private final StringBuilder textBuffer = new StringBuilder();

        WeComListener(String agentId, String agentKey, String generation,
                      Map<String, Object> config, ChannelInboundDispatcher dispatcher) {
            this.agentId = agentId;
            this.agentKey = agentKey;
            this.generation = generation;
            this.config = new LinkedHashMap<>(config);
            this.dispatcher = dispatcher;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!isActive(agentKey, generation)) {
                webSocket.abort();
                return;
            }
            WebSocket old = sockets.put(agentKey, webSocket);
            if (old != null && old != webSocket) old.abort();
            Map<String, Object> auth = Map.of(
                    "cmd", SUBSCRIBE,
                    "headers", Map.of("req_id", requestId(SUBSCRIBE)),
                    "body", Map.of(
                            "bot_id", stringValue(config.get("bot_id")),
                            "secret", stringValue(config.get("secret"))));
            webSocket.sendText(JsonUtils.toJson(auth), true).whenComplete((ignored, error) -> {
                if (error == null) return;
                disconnected(agentId, agentKey, generation, config, dispatcher, webSocket,
                        "WeCom authentication frame failed: " + errorMessage(error));
            });
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String raw = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleFrame(webSocket, ChannelHttpSupport.json(raw));
                } catch (Exception error) {
                    recordError(agentKey, "WeCom frame handling failed", error);
                }
            }
            webSocket.request(1);
            return null;
        }

        private void handleFrame(WebSocket webSocket, Map<String, Object> frame) {
            if (!isActive(agentKey, generation) || sockets.get(agentKey) != webSocket) return;
            String command = stringValue(frame.get("cmd"));
            Map<?, ?> headers = ChannelHttpSupport.map(frame.get("headers"));
            String reqId = ChannelHttpSupport.first(headers, "req_id");
            if (CALLBACK.equals(command)) {
                Map<String, Object> body = ChannelHttpSupport.copy(ChannelHttpSupport.map(frame.get("body")));
                String messageId = ChannelHttpSupport.first(body, "msgid", "message_id");
                if (duplicate(agentKey, messageId)) return;
                ChannelInboundMessage inbound = parseWebhook(agentId, body, Map.of("wecom_req_id", reqId));
                if (!inbound.getContent().isBlank() || !inbound.getAttachments().isEmpty()) {
                    dispatcher.dispatch(inbound, 20);
                }
                return;
            }
            if (EVENT_CALLBACK.equals(command)) {
                handleEvent(webSocket, frame, reqId);
                return;
            }
            PendingAck pending = pendingAcks.remove(reqId);
            if (pending != null) {
                pending.future().complete(frame);
                return;
            }
            if (reqId.startsWith(SUBSCRIBE)) {
                int errcode = intValue(frame.get("errcode"));
                if (errcode == 0) {
                    ready.add(agentKey);
                    reconnectAttempts.remove(agentKey);
                    lastErrors.remove(agentKey);
                    scheduleHeartbeat(agentId, agentKey, generation, config, dispatcher, webSocket);
                    log.info("WeCom authenticated: agent={}, bot={}", agentKey,
                            stringValue(config.get("bot_id")));
                } else {
                    desired.remove(agentKey);
                    ready.remove(agentKey);
                    active.remove(agentKey, generation);
                    sockets.remove(agentKey, webSocket);
                    cancelHeartbeat(agentKey);
                    recordError(agentKey, "WeCom authentication failed: " + errcode + " "
                            + stringValue(frame.get("errmsg")), null);
                    webSocket.abort();
                }
                return;
            }
            if (reqId.startsWith(HEARTBEAT)) {
                if (intValue(frame.get("errcode")) == 0) {
                    lastPongs.put(agentKey, System.currentTimeMillis());
                }
            }
        }

        private void handleEvent(WebSocket webSocket, Map<String, Object> frame, String reqId) {
            Map<?, ?> body = ChannelHttpSupport.map(frame.get("body"));
            Map<?, ?> event = ChannelHttpSupport.map(body.get("event"));
            String eventType = ChannelHttpSupport.first(event, "eventtype");
            String welcome = stringValue(config.get("welcome_text"));
            if (!"enter_chat".equals(eventType) || welcome.isBlank()) return;
            Map<String, Object> response = Map.of(
                    "cmd", RESPOND_WELCOME,
                    "headers", Map.of("req_id", reqId),
                    "body", Map.of("msgtype", "text", "text", Map.of("content", welcome)));
            webSocket.sendText(JsonUtils.toJson(response), true);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected(agentId, agentKey, generation, config, dispatcher, webSocket,
                    "WeCom WebSocket closed: " + statusCode + " " + stringValue(reason));
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected(agentId, agentKey, generation, config, dispatcher, webSocket,
                    "WeCom WebSocket error: " + errorMessage(error));
        }
    }
}
