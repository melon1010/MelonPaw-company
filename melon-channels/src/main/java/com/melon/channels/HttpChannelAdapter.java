package com.melon.channels;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import com.lark.oapi.ws.Client;
import com.lark.oapi.ws.ClientLifecycle;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.OutboundNetworkPolicy;
import com.melon.core.util.ExecutorUtils;
import com.melon.core.util.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.melon.core.util.ValueUtils.stringValue;

/** Official long-connection receiver plus HTTP sender for DingTalk and Feishu. */
public class HttpChannelAdapter extends BasicChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpChannelAdapter.class);
    private static final int PROCESSED_LIMIT = 2000;
    private static final Duration FEISHU_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final String STARTING = "starting";
    private static final String RUNNING = "running";
    private static final String RECONNECTING = "reconnecting";

    private final ExecutorService worker;
    private final boolean ownsWorker;
    private final Map<String, OpenDingTalkClient> dingtalkClients = new ConcurrentHashMap<>();
    private final Map<String, Client> feishuClients = new ConcurrentHashMap<>();
    private final Map<String, String> connectionErrors = new ConcurrentHashMap<>();
    private final Map<String, String> connectionStates = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> activeConnectionConfigs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> processed = new ConcurrentHashMap<>();

    public HttpChannelAdapter(String type, boolean qrcode, boolean streaming, boolean webhook) {
        this(type, qrcode, streaming, webhook, standaloneWorker(), true);
    }

    HttpChannelAdapter(
            String type, boolean qrcode, boolean streaming, boolean webhook, ExecutorService worker) {
        this(type, qrcode, streaming, webhook, worker, false);
    }

    private HttpChannelAdapter(
            String type, boolean qrcode, boolean streaming, boolean webhook,
            ExecutorService worker, boolean ownsWorker) {
        super(requireDomesticHttpType(type), true, qrcode, streaming, webhook);
        this.worker = java.util.Objects.requireNonNull(worker);
        this.ownsWorker = ownsWorker;
    }

    @Override
    public CompletableFuture<ChannelHealth> start(String agentId,
                                                  Map<String, Object> config,
                                                  ChannelInboundDispatcher dispatcher) {
        Map<String, Object> runtime = new LinkedHashMap<>(config != null ? config : Map.of());
        List<String> missing = missingRequired(runtime);
        if (!Boolean.TRUE.equals(runtime.get("enabled")) || !missing.isEmpty()) {
            stopConnection(agentId);
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        if (matchesActiveConnection(agentId, runtime)) {
            return CompletableFuture.completedFuture(health(agentId, runtime));
        }
        return CompletableFuture.supplyAsync(() -> {
            stopConnection(agentId);
            try {
                connectionStates.put(key(agentId), STARTING);
                if ("dingtalk".equals(type())) {
                    startDingTalk(agentId, runtime, dispatcher);
                } else {
                    startFeishu(agentId, runtime, dispatcher);
                }
                super.start(agentId, runtime).join();
                activeConnectionConfigs.put(key(agentId), connectionConfig(runtime));
                connectionErrors.remove(key(agentId));
                return health(agentId, runtime);
            } catch (Exception error) {
                stopConnection(agentId);
                String detail = error.getMessage() != null
                        ? error.getMessage() : error.getClass().getSimpleName();
                connectionErrors.put(key(agentId), detail);
                throw new CompletionException(error);
            }
        }, worker);
    }

    @Override
    public CompletableFuture<ChannelHealth> stop(String agentId) {
        stopConnection(agentId);
        connectionErrors.remove(key(agentId));
        processed.remove(key(agentId));
        return super.stop(agentId);
    }

    @Override
    public ChannelHealth health(String agentId, Map<String, Object> config) {
        boolean enabled = Boolean.TRUE.equals(config != null ? config.get("enabled") : null);
        if (!enabled) {
            return ChannelHealth.of(type(), "stopped", false, true, true, "Channel is disabled.");
        }
        List<String> missing = missingRequired(config != null ? config : Map.of());
        if (!missing.isEmpty()) {
            return ChannelHealth.of(type(), "misconfigured", false, false, true,
                    "Missing required config: " + String.join(", ", missing));
        }
        String error = connectionErrors.get(key(agentId));
        if (error != null && !error.isBlank()) {
            return ChannelHealth.of(type(), "unhealthy", false, false, true, error);
        }
        String state = connectionStates.getOrDefault(key(agentId), STARTING);
        boolean connected = "dingtalk".equals(type())
                ? RUNNING.equals(state)
                : ClientLifecycle.connected(feishuClients.get(key(agentId)));
        if (connected) state = RUNNING;
        return ChannelHealth.of(type(), state, connected,
                connected, true, connected
                        ? displayName() + " long connection is active."
                        : RECONNECTING.equals(state)
                                ? displayName() + " long connection is reconnecting."
                                : displayName() + " long connection is starting.");
    }

    @Override
    public ChannelInboundMessage parseWebhook(String agentId, Map<String, Object> body,
                                              Map<String, String> headers) {
        Map<String, Object> payload = body != null ? body : Map.of();
        return switch (type()) {
            case "feishu" -> parseFeishu(agentId, payload, headers);
            case "dingtalk" -> parseDingTalk(agentId, payload, headers);
            default -> throw new IllegalStateException("Unsupported HTTP channel: " + type());
        };
    }

    @Override
    public CompletableFuture<ChannelOutboundMessage> send(ChannelOutboundMessage message,
                                                          Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config != null ? config.get("enabled") : null)) {
            mark(message, "skipped", "channel disabled");
            return CompletableFuture.completedFuture(message);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> response = switch (type()) {
                    case "feishu" -> sendFeishu(message, config);
                    case "dingtalk" -> sendDingTalk(message, config);
                    default -> throw new IllegalStateException("Unsupported HTTP channel: " + type());
                };
                boolean sent = response.statusCode() >= 200 && response.statusCode() < 300
                        && platformAccepted(response.body());
                mark(message, sent ? "sent" : "failed",
                        response.statusCode() + " " + response.body());
            } catch (Exception e) {
                mark(message, "failed",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            return message;
        }, worker);
    }

    @Override
    public void close() {
        dingtalkClients.values().forEach(this::stopDingTalk);
        dingtalkClients.clear();
        feishuClients.values().forEach(ClientLifecycle::stop);
        feishuClients.clear();
        connectionStates.clear();
        activeConnectionConfigs.clear();
        if (ownsWorker) ExecutorUtils.shutdownGracefully(worker, Duration.ofSeconds(5));
    }

    private static ExecutorService standaloneWorker() {
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                new NamedThreadFactory("melon-http-channel"), new ThreadPoolExecutor.AbortPolicy());
    }

    private void startDingTalk(String agentId, Map<String, Object> config,
                               ChannelInboundDispatcher dispatcher) throws Exception {
        OpenDingTalkCallbackListener<ChatbotMessage, Void> listener = message -> {
            try {
                connectionStates.put(key(agentId), RUNNING);
                Map<String, Object> payload = dingTalkPayload(message);
                dispatch(agentId, parseDingTalk(agentId, payload, Map.of()), dispatcher,
                        stringValue(payload.get("msgId")));
            } catch (Exception error) {
                log.error("DingTalk inbound callback failed: agent={}", agentId, error);
            }
            return null;
        };
        OpenDingTalkClient client = OpenDingTalkStreamClientBuilder.custom()
                .credential(new AuthClientCredential(
                        stringValue(config.get("client_id")),
                        stringValue(config.get("client_secret"))))
                .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, listener)
                .build();
        dingtalkClients.put(key(agentId), client);
        client.start();
        log.info("DingTalk Stream client started: agent={}", agentId);
    }

    private void startFeishu(String agentId, Map<String, Object> config,
                              ChannelInboundDispatcher dispatcher) throws Exception {
        EventDispatcher eventDispatcher = EventDispatcher.newBuilder(
                        stringValue(config.get("verification_token")),
                        stringValue(config.get("encrypt_key")))
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        try {
                            Map<String, Object> payload = feishuPayload(event, config);
                            Map<?, ?> message = map(map(payload.get("event")).get("message"));
                            dispatch(agentId, parseFeishu(agentId, payload, Map.of()), dispatcher,
                                    stringValue(message.get("message_id")));
                        } catch (Exception error) {
                            log.error("Feishu inbound callback failed: agent={}", agentId, error);
                        }
                    }
                })
                .build();
        String domain = "lark".equalsIgnoreCase(stringValue(config.get("domain")))
                ? "https://open.larksuite.com" : "https://open.feishu.cn";
        Client client = new Client.Builder(
                stringValue(config.get("app_id")), stringValue(config.get("app_secret")))
                .domain(domain)
                .autoReconnect(true)
                .onReconnecting(() -> connectionStates.put(key(agentId), RECONNECTING))
                .onReconnected(() -> connectionStates.put(key(agentId), RUNNING))
                .eventHandler(eventDispatcher)
                .build();
        feishuClients.put(key(agentId), client);
        client.start();
        ClientLifecycle.awaitReady(client, FEISHU_READY_TIMEOUT);
        connectionStates.put(key(agentId), RUNNING);
        log.info("Feishu WebSocket connected: agent={}, domain={}", agentId, domain);
    }

    private Map<String, Object> dingTalkPayload(ChatbotMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", stringValue(message.getConversationId()));
        payload.put("conversationType", stringValue(message.getConversationType()));
        payload.put("senderStaffId", stringValue(message.getSenderStaffId()));
        payload.put("senderId", stringValue(message.getSenderId()));
        payload.put("senderNick", stringValue(message.getSenderNick()));
        payload.put("sessionWebhook", stringValue(message.getSessionWebhook()));
        payload.put("sessionWebhookExpiredTime", message.getSessionWebhookExpiredTime());
        payload.put("msgId", stringValue(message.getMsgId()));
        payload.put("msgtype", stringValue(message.getMsgtype()));
        payload.put("isInAtList", Boolean.TRUE.equals(message.getInAtList()));
        String text = message.getText() != null ? stringValue(message.getText().getContent()) : "";
        if (text.isBlank() && message.getContent() != null) {
            text = stringValue(message.getContent().getContent(), message.getContent().getRecognition());
        }
        payload.put("text", Map.of("content", text));
        return payload;
    }

    private Map<String, Object> feishuPayload(P2MessageReceiveV1 event,
                                               Map<String, Object> config) {
        P2MessageReceiveV1Data data = event != null ? event.getEvent() : null;
        EventMessage sourceMessage = data != null ? data.getMessage() : null;
        EventSender sourceSender = data != null ? data.getSender() : null;
        UserId sourceId = sourceSender != null ? sourceSender.getSenderId() : null;
        Map<String, Object> message = new LinkedHashMap<>();
        if (sourceMessage != null) {
            message.put("message_id", stringValue(sourceMessage.getMessageId()));
            message.put("chat_id", stringValue(sourceMessage.getChatId()));
            message.put("chat_type", stringValue(sourceMessage.getChatType()));
            message.put("message_type", stringValue(sourceMessage.getMessageType()));
            message.put("content", stringValue(sourceMessage.getContent()));
            message.put("mentions", sourceMessage.getMentions() == null
                    ? List.of() : List.of(sourceMessage.getMentions()));
        }
        Map<String, Object> senderId = new LinkedHashMap<>();
        if (sourceId != null) {
            senderId.put("open_id", stringValue(sourceId.getOpenId()));
            senderId.put("user_id", stringValue(sourceId.getUserId()));
            senderId.put("union_id", stringValue(sourceId.getUnionId()));
        }
        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("sender_id", senderId);
        sender.put("sender_type", sourceSender != null
                ? stringValue(sourceSender.getSenderType()) : "");
        return Map.of(
                "app_id", stringValue(config.get("app_id")),
                "event", Map.of("message", message, "sender", sender));
    }

    private void dispatch(String agentId, ChannelInboundMessage inbound,
                          ChannelInboundDispatcher dispatcher, String messageId) {
        if (dispatcher == null || duplicate(agentId, messageId)) return;
        dispatcher.dispatch(inbound, 20).whenComplete((ignored, error) -> {
            if (error != null) {
                log.error("{} inbound processing failed: agent={}, message={}",
                        displayName(), agentId, messageId, error);
            }
        });
    }

    private boolean duplicate(String agentId, String messageId) {
        if (messageId == null || messageId.isBlank()) return false;
        Map<String, Long> ids = processed.computeIfAbsent(key(agentId),
                ignored -> new ConcurrentHashMap<>());
        Long previous = ids.putIfAbsent(messageId, System.currentTimeMillis());
        if (ids.size() > PROCESSED_LIMIT) {
            ids.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(ids.size() - PROCESSED_LIMIT)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(ids::remove);
        }
        return previous != null;
    }

    private void stopConnection(String agentId) {
        stopDingTalk(dingtalkClients.remove(key(agentId)));
        ClientLifecycle.stop(feishuClients.remove(key(agentId)));
        connectionStates.remove(key(agentId));
        activeConnectionConfigs.remove(key(agentId));
    }

    private void stopDingTalk(OpenDingTalkClient client) {
        if (client == null) return;
        try {
            client.stop();
        } catch (Exception error) {
            log.warn("DingTalk Stream stop failed", error);
        }
    }

    private boolean platformAccepted(String body) {
        try {
            Map<String, Object> parsed = JsonUtils.fromJson(body, Map.class);
            if (parsed == null || parsed.isEmpty()) return true;
            if ("feishu".equals(type()) && parsed.containsKey("code")) {
                return number(parsed.get("code")) == 0;
            }
            if ("dingtalk".equals(type()) && parsed.containsKey("errcode")) {
                return number(parsed.get("errcode")) == 0;
            }
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String displayName() {
        return "dingtalk".equals(type()) ? "DingTalk" : "Feishu";
    }

    private String key(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId.trim();
    }

    private boolean matchesActiveConnection(String agentId, Map<String, Object> config) {
        String agentKey = key(agentId);
        boolean clientExists = "dingtalk".equals(type())
                ? dingtalkClients.containsKey(agentKey)
                : feishuClients.containsKey(agentKey);
        return clientExists && connectionConfig(config).equals(activeConnectionConfigs.get(agentKey));
    }

    private Map<String, String> connectionConfig(Map<String, Object> config) {
        List<String> keys = "dingtalk".equals(type())
                ? List.of("client_id", "client_secret")
                : List.of("app_id", "app_secret", "verification_token", "encrypt_key", "domain");
        Map<String, String> result = new LinkedHashMap<>();
        keys.forEach(name -> result.put(name, stringValue(config.get(name))));
        return result;
    }

    @Override
    public Map<String, Object> qrcode(String agentId, Map<String, Object> config) {
        return switch (type()) {
            case "dingtalk" -> dingtalkQrcode();
            case "feishu" -> feishuQrcode(config != null ? config : Map.of());
            default -> throw new IllegalStateException("Unsupported HTTP channel: " + type());
        };
    }

    @Override
    public Map<String, Object> qrcodeStatus(String agentId, Map<String, Object> config, String token) {
        return switch (type()) {
            case "dingtalk" -> dingtalkQrcodeStatus(token);
            case "feishu" -> feishuQrcodeStatus(config != null ? config : Map.of(), token);
            default -> throw new IllegalStateException("Unsupported HTTP channel: " + type());
        };
    }

    private Map<String, Object> dingtalkQrcode() {
        try {
            String base = "https://oapi.dingtalk.com";
            HttpResponse<String> init = post(
                    base + "/app/registration/init", Map.of(), Map.of("source", "MELONPAW"), Map.of());
            Map<String, Object> initBody = JsonUtils.fromJson(init.body(), Map.class);
            String nonce = initBody != null ? stringValue(initBody.get("nonce")) : "";
            if (nonce.isBlank()) {
                return failedQrcode("DingTalk returned empty nonce: " + init.body());
            }
            HttpResponse<String> begin = post(
                    base + "/app/registration/begin", Map.of(), Map.of("nonce", nonce), Map.of());
            Map<String, Object> beginBody = JsonUtils.fromJson(begin.body(), Map.class);
            String deviceCode = beginBody != null ? stringValue(beginBody.get("device_code")) : "";
            String scanUrl = beginBody != null ? stringValue(beginBody.get("verification_uri_complete")) : "";
            if (deviceCode.isBlank() || scanUrl.isBlank()) {
                return failedQrcode("DingTalk returned empty device_code or URI: " + begin.body());
            }
            return Map.of(
                    "qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl),
                    "poll_token", deviceCode,
                    "enabled", true,
                    "status", "waiting",
                    "detail", "");
        } catch (Exception e) {
            return failedQrcode(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> dingtalkQrcodeStatus(String token) {
        try {
            HttpResponse<String> response = post(
                    "https://oapi.dingtalk.com/app/registration/poll",
                    Map.of(), Map.of("device_code", token), Map.of());
            Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
            String status = body != null ? stringValue(body.get("status"), "WAITING") : "WAITING";
            if ("SUCCESS".equals(status)) {
                return Map.of("status", "success", "credentials", Map.of(
                        "client_id", stringValue(body.get("client_id")),
                        "client_secret", stringValue(body.get("client_secret"))));
            }
            if ("FAIL".equals(status)) {
                return Map.of("status", "fail", "credentials",
                        Map.of("fail_reason", stringValue(body.get("fail_reason"))));
            }
            if ("EXPIRED".equals(status)) {
                return Map.of("status", "expired", "credentials", Map.of());
            }
            return Map.of("status", "waiting", "credentials", Map.of());
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of(
                    "fail_reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> feishuQrcode(Map<String, Object> config) {
        try {
            String endpoint = feishuAccounts(config) + "/oauth/v1/app/registration";
            HttpResponse<String> init = postForm(endpoint, Map.of("action", "init"), config);
            Map<String, Object> initBody = JsonUtils.fromJson(init.body(), Map.class);
            Object methods = initBody != null ? initBody.get("supported_auth_methods") : null;
            if (!(methods instanceof List<?> list) || !list.contains("client_secret")) {
                return failedQrcode("Feishu unsupported auth methods: " + init.body());
            }
            HttpResponse<String> begin = postForm(endpoint, Map.of(
                    "action", "begin",
                    "archetype", "PersonalAgent",
                    "auth_method", "client_secret",
                    "request_user_info", "open_id"), config);
            Map<String, Object> beginBody = JsonUtils.fromJson(begin.body(), Map.class);
            String deviceCode = beginBody != null ? stringValue(beginBody.get("device_code")) : "";
            String scanUrl = beginBody != null ? stringValue(beginBody.get("verification_uri_complete")) : "";
            if (deviceCode.isBlank() || scanUrl.isBlank()) {
                return failedQrcode("Feishu missing device_code or QR URL: " + begin.body());
            }
            scanUrl += scanUrl.contains("?") ? "&source=MelonPaw" : "?source=MelonPaw";
            return Map.of(
                    "qrcode_img", ChannelHttpSupport.qrPngBase64(scanUrl),
                    "poll_token", deviceCode,
                    "enabled", true,
                    "status", "waiting",
                    "detail", "");
        } catch (Exception e) {
            return failedQrcode(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> feishuQrcodeStatus(Map<String, Object> config, String token) {
        try {
            HttpResponse<String> response = postForm(
                    feishuAccounts(config) + "/oauth/v1/app/registration",
                    Map.of("action", "poll", "device_code", token), config);
            Map<String, Object> body = JsonUtils.fromJson(response.body(), Map.class);
            if (body != null
                    && !stringValue(body.get("client_id")).isBlank()
                    && !stringValue(body.get("client_secret")).isBlank()) {
                Map<?, ?> userInfo = map(body.get("user_info"));
                return Map.of("status", "success", "credentials", Map.of(
                        "app_id", stringValue(body.get("client_id")),
                        "app_secret", stringValue(body.get("client_secret")),
                        "open_id", stringValue(userInfo.get("open_id")),
                        "tenant_brand", stringValue(
                                userInfo.get("tenant_brand"), stringValue(config.get("domain"), "feishu"))));
            }
            String error = body != null ? stringValue(body.get("error")) : "";
            if ("expired_token".equals(error) || "invalid_grant".equals(error)) {
                return Map.of("status", "expired", "credentials", Map.of("fail_reason", "QR code expired"));
            }
            if ("access_denied".equals(error)) {
                return Map.of("status", "fail", "credentials", Map.of("fail_reason", "User denied authorization"));
            }
            if (!error.isBlank() && !"authorization_pending".equals(error) && !"slow_down".equals(error)) {
                return Map.of("status", "fail", "credentials", Map.of("fail_reason", error));
            }
            return Map.of("status", "waiting", "credentials", Map.of());
        } catch (Exception e) {
            return Map.of("status", "fail", "credentials", Map.of(
                    "fail_reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private HttpResponse<String> sendFeishu(ChannelOutboundMessage message,
                                            Map<String, Object> config) throws Exception {
        String domain = "lark".equals(stringValue(config.get("domain")))
                ? "https://open.larksuite.com" : "https://open.feishu.cn";
        HttpResponse<String> tokenResponse = post(
                domain + "/open-apis/auth/v3/tenant_access_token/internal",
                Map.of(),
                Map.of(
                        "app_id", stringValue(config.get("app_id")),
                        "app_secret", stringValue(config.get("app_secret"))),
                config);
        Map<String, Object> tokenBody = JsonUtils.fromJson(tokenResponse.body(), Map.class);
        String token = tokenBody != null ? stringValue(tokenBody.get("tenant_access_token")) : "";
        if (token.isBlank()) {
            throw new IllegalStateException("Feishu tenant_access_token is empty: " + tokenResponse.body());
        }
        String receiveId = target(message, "receive_id", "chat_id", "chatId", "open_chat_id", "open_id");
        String receiveIdType = meta(message, "feishu_receive_id_type", "receive_id_type");
        if (receiveIdType.isBlank()) {
            receiveIdType = "chat_id";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", receiveId);
        body.put("msg_type", "text");
        body.put("content", JsonUtils.toJson(Map.of("text", message.getText())));
        return post(domain + "/open-apis/im/v1/messages?receive_id_type=" + encode(receiveIdType),
                Map.of("Authorization", "Bearer " + token), body, config);
    }

    private HttpResponse<String> sendDingTalk(ChannelOutboundMessage message,
                                              Map<String, Object> config) throws Exception {
        String url = meta(message, "sessionWebhook", "reply_url", "webhook_url");
        if (url.isBlank()) {
            url = first(config, "webhook_url", "outgoing_webhook", "reply_url");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of("title", "MelonPaw-Company", "text", message.getText()));
        return post(url, Map.of(), body, config);
    }

    private ChannelInboundMessage parseFeishu(String agentId, Map<String, Object> payload,
                                              Map<String, String> headers) {
        Map<?, ?> event = map(payload.get("event"));
        Map<?, ?> message = map(event.get("message"));
        Map<?, ?> sender = map(event.get("sender"));
        Map<?, ?> senderId = map(sender.get("sender_id"));
        String chatId = stringValue(message.get("chat_id"));
        String content = stringValue(message.get("content"));
        Map<?, ?> contentMap = JsonUtils.fromJson(content, Map.class);
        String text = contentMap != null ? stringValue(contentMap.get("text"), content) : content;
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(stringValue(senderId.get("open_id"), stringValue(senderId.get("user_id"))));
        String chatType = stringValue(message.get("chat_type"), stringValue(message.get("chatType"), "p2p"));
        String appId = first(payload, "app_id", "appId");
        inbound.setSessionId("group".equals(chatType) && !chatId.isBlank()
                ? (appId.length() >= 4 ? appId.substring(appId.length() - 4) + "_" : "") + shortId(chatId)
                : shortId(inbound.getUserId().isBlank() ? chatId : inbound.getUserId()));
        inbound.setContent(text);
        Map<String, Object> meta = copy(payload);
        meta.put("feishu_chat_id", chatId);
        meta.put("feishu_chat_type", chatType);
        meta.put("feishu_sender_id", inbound.getUserId());
        meta.put("feishu_message_id",
                stringValue(message.get("message_id"), stringValue(message.get("messageId"))));
        meta.put("message_id", meta.get("feishu_message_id"));
        meta.put("is_group", "group".equals(chatType));
        meta.put("feishu_receive_id", "group".equals(chatType) ? chatId : inbound.getUserId());
        meta.put("feishu_receive_id_type", "group".equals(chatType) ? "chat_id" : "open_id");
        if (feishuMentionsBot(content, message)) {
            meta.put("bot_mentioned", true);
        }
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress(
                "chat",
                "group".equals(chatType) ? chatId : inbound.getUserId(),
                Map.of(
                        "chat_id", chatId,
                        "open_id", inbound.getUserId(),
                        "receive_id", meta.get("feishu_receive_id"),
                        "receive_id_type", meta.get("feishu_receive_id_type"),
                        "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private ChannelInboundMessage parseDingTalk(String agentId, Map<String, Object> payload,
                                                Map<String, String> headers) {
        String sessionWebhook = first(payload, "sessionWebhook", "session_webhook", "reply_url");
        String conversation = first(payload, "conversationId", "conversation_id", "chat_id");
        String sender = first(payload, "senderStaffId", "senderId", "sender_id", "user_id");
        String conversationType = dingtalkConversationType(payload);
        String senderName = first(payload, "senderNick", "sender_nick", "senderName", "user_name");
        String displaySender = displaySender(senderName, sender);
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId(agentId);
        inbound.setChannel(type());
        inbound.setUserId(displaySender);
        inbound.setSessionId(shortId(conversation));
        inbound.setContent(dingtalkText(payload));
        Map<String, Object> meta = copy(payload);
        meta.put("conversation_id", conversation);
        meta.put("conversation_type", conversationType);
        meta.put("sender_staff_id", sender);
        meta.put("sender_id", sender);
        meta.put("user_name", senderName);
        meta.put("message_id", first(payload, "msgId", "msg_id", "message_id"));
        meta.put("is_group", "group".equals(conversationType));
        if (Boolean.TRUE.equals(payload.get("isInAtList"))
                || Boolean.TRUE.equals(payload.get("is_in_at_list"))) {
            meta.put("bot_mentioned", true);
        }
        if (inbound.getContent().startsWith("/")) {
            meta.put("has_bot_command", true);
        }
        inbound.setChannelMeta(meta);
        inbound.setReplyTo(new ChannelAddress(
                "conversation", conversation,
                Map.of(
                        "sessionWebhook", sessionWebhook,
                        "headers", headers != null ? headers : Map.of())));
        return inbound;
    }

    private Map<String, Object> failedQrcode(String detail) {
        return Map.of(
                "qrcode_img", "",
                "poll_token", "",
                "enabled", false,
                "status", "failed",
                "detail", detail == null ? "" : detail);
    }

    private String feishuAccounts(Map<String, Object> config) {
        return "lark".equals(stringValue(config.get("domain")))
                ? "https://accounts.larksuite.com" : "https://accounts.feishu.cn";
    }

    private List<String> missingRequired(Map<String, Object> config) {
        return switch (type()) {
            case "feishu" -> missing(config, "app_id", "app_secret");
            case "dingtalk" -> missing(config, "client_id", "client_secret");
            default -> throw new IllegalStateException("Unsupported HTTP channel: " + type());
        };
    }

    private boolean feishuMentionsBot(String content, Map<?, ?> message) {
        return content != null && content.contains("@_all")
                || !list(message.get("mentions")).isEmpty();
    }

    private String dingtalkConversationType(Map<String, Object> payload) {
        String raw = first(payload, "conversationType", "conversation_type");
        return "2".equals(raw) || "group".equalsIgnoreCase(raw) ? "group" : "dm";
    }

    private String dingtalkText(Map<String, Object> payload) {
        Object text = payload.get("text");
        if (text instanceof Map<?, ?> map) {
            String content = stringValue(map.get("content"));
            if (!content.isBlank()) {
                return content;
            }
        }
        return first(payload, "content", "message", "text");
    }

    private String displaySender(String nickname, String senderId) {
        String suffix = senderId != null && senderId.length() >= 4
                ? senderId.substring(senderId.length() - 4)
                : stringValue(senderId, "????");
        return (nickname == null || nickname.isBlank() ? "unknown" : nickname) + "#" + suffix;
    }

    private String shortId(String value) {
        String text = stringValue(value);
        return text.length() >= 8 ? text.substring(text.length() - 8) : text;
    }

    private List<String> missing(Map<String, Object> config, String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (stringValue(config.get(key)).isBlank()) {
                result.add(key);
            }
        }
        return result;
    }

    private HttpResponse<String> post(String url, Map<String, String> headers,
                                      Object body, Map<String, Object> config) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Missing outbound URL for channel " + type());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(OutboundNetworkPolicy.requirePublicHttpUri(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return client(config).send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String url, Map<String, String> body,
                                          Map<String, Object> config) throws Exception {
        String form = body.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        HttpRequest request = HttpRequest.newBuilder(OutboundNetworkPolicy.requirePublicHttpUri(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return client(config).send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient client(Map<String, Object> config) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    private String target(ChannelOutboundMessage message, String... metaKeys) {
        String target = message.getTo() != null ? stringValue(message.getTo().getId()) : "";
        if (!target.isBlank() && !"default".equals(target)) {
            return target;
        }
        if (message.getTo() != null && message.getTo().getExtra() != null) {
            for (String key : metaKeys) {
                String value = stringValue(message.getTo().getExtra().get(key));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        String metadataTarget = meta(message, metaKeys);
        if (!metadataTarget.isBlank()) {
            return metadataTarget;
        }
        throw new IllegalArgumentException("Missing target for channel " + type());
    }

    private String meta(ChannelOutboundMessage message, String... keys) {
        Object raw = message.getMeta() != null ? message.getMeta().get("channel_meta") : null;
        Map<?, ?> metadata = map(raw);
        for (String key : keys) {
            String value = stringValue(metadata.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String first(Map<String, Object> map, String... keys) {
        if (map == null) {
            return "";
        }
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<?, ?> map(Object raw) {
        return raw instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<?> list(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    private Map<String, Object> copy(Map<String, Object> payload) {
        return new LinkedHashMap<>(payload);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void mark(ChannelOutboundMessage message, String status, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>(message.getMeta());
        meta.put("delivery_status", status);
        meta.put("delivery_detail", detail);
        message.setMeta(meta);
    }

    private static String requireDomesticHttpType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase();
        if (!"dingtalk".equals(normalized) && !"feishu".equals(normalized)) {
            throw new IllegalArgumentException("HTTP channel is not available in domestic deployment: " + type);
        }
        return normalized;
    }
}
