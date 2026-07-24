package com.melon.channels;

import com.melon.core.config.ConfigManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomesticChannelTest {

    @TempDir
    Path tempDir;

    @Test
    void registryContainsOnlyDomesticChannelsAndRejectsFallbackAdapters() {
        try (ChannelAdapterRegistry registry = new ChannelAdapterRegistry()) {
            assertEquals(new LinkedHashSet<>(ChannelTypes.DOMESTIC), registry.all().keySet());
            for (String removed : List.of(
                    "imessage", "discord", "telegram", "matrix", "slack",
                    "mattermost", "mqtt", "voice", "sip", "custom-webhook",
                    "onebot", "yuanbao", "xiaoyi")) {
                assertFalse(ChannelTypes.supported(removed));
                assertThrows(IllegalArgumentException.class, () -> registry.get(removed));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new HttpChannelAdapter("telegram", false, true, true));

            ChannelAdapter qq = registry.get("qq");
            assertTrue(qq.supportsQrcode());
            assertFalse(qq.requiresWebhook());
            assertTrue(registry.get("wechat").supportsQrcode());
            assertTrue(registry.get("wecom").supportsQrcode());
            assertTrue(registry.get("dingtalk").supportsStreaming());
            assertFalse(registry.get("dingtalk").requiresWebhook());
            assertTrue(registry.get("feishu").supportsStreaming());
            assertFalse(registry.get("feishu").requiresWebhook());
        }
    }

    @Test
    void configListsDomesticDefaultsAndRejectsRemovedChannels() {
        ConfigManager configManager = configManager();
        try (ChannelAdapterRegistry registry = new ChannelAdapterRegistry()) {
            ChannelConfigService service = new ChannelConfigService(configManager, registry);
            Map<String, Map<String, Object>> listed = service.list("default");

            assertEquals(new LinkedHashSet<>(ChannelTypes.DOMESTIC), listed.keySet());
            assertTrue(Boolean.TRUE.equals(listed.get("console").get("enabled")));
            assertTrue(listed.get("feishu").containsKey("verification_token"));
            assertFalse(listed.containsKey("onebot"));
            assertFalse(listed.containsKey("yuanbao"));
            assertFalse(listed.containsKey("xiaoyi"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.update("default", "slack", Map.of("enabled", true)));

            ChannelAccessControlStore accessControl = new ChannelAccessControlStore(configManager);
            assertThrows(IllegalArgumentException.class,
                    () -> accessControl.channel("default", "slack"));

            Map<String, Object> saved = service.update("default", "feishu", Map.of(
                    "enabled", true,
                    "app_id", "cli-test",
                    "app_secret", "secret-value"));
            assertEquals("********", saved.get("app_secret"));
            service.update("default", "feishu", Map.of("app_secret", "********"));
            assertEquals("secret-value", service.runtimeConfig("default", "feishu").get("app_secret"));
        }
    }

    @Test
    void qrAuthorizedWechatEndpointIsPersistedButCannotBeInjectedByRegularUpdates() {
        ConfigManager configManager = configManager();
        try (ChannelAdapterRegistry registry = new ChannelAdapterRegistry()) {
            ChannelConfigService service = new ChannelConfigService(configManager, registry);

            Map<String, Object> authorized = service.updateAuthorizedCredentials("default", "wechat", Map.of(
                    "bot_token", "wechat-token",
                    "base_url", "https://ilinkai.weixin.qq.com/"));

            assertFalse(authorized.containsKey("base_url"));
            assertEquals("https://ilinkai.weixin.qq.com",
                    service.runtimeConfig("default", "wechat").get("base_url"));

            service.update("default", "wechat", Map.of(
                    "enabled", true,
                    "base_url", "https://attacker.example"));
            assertEquals("https://ilinkai.weixin.qq.com",
                    service.runtimeConfig("default", "wechat").get("base_url"));

            assertThrows(IllegalArgumentException.class,
                    () -> service.updateAuthorizedCredentials("default", "wechat", Map.of(
                            "bot_token", "wechat-token",
                            "base_url", "https://attacker.example")));
        }
    }

    @Test
    void wechatQrStatusKeepsOfficialConfirmedStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/get_qrcode_status", exchange -> {
            byte[] response = ("{\"status\":\"confirmed\",\"bot_token\":\"token-from-qr\","
                    + "\"baseurl\":\"https://ilinkai.weixin.qq.com\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String previous = System.getProperty("melon.outbound.allow-private-for-tests");
        System.setProperty("melon.outbound.allow-private-for-tests", "true");
        try (ChannelAdapterRegistry registry = new ChannelAdapterRegistry()) {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, Object> status = registry.get("wechat").qrcodeStatus(
                    "default", Map.of("base_url", baseUrl), "qr-token");
            assertEquals("confirmed", status.get("status"));
            assertEquals("token-from-qr",
                    ((Map<?, ?>) status.get("credentials")).get("bot_token"));
        } finally {
            if (previous == null) {
                System.clearProperty("melon.outbound.allow-private-for-tests");
            } else {
                System.setProperty("melon.outbound.allow-private-for-tests", previous);
            }
            server.stop(0);
        }
    }

    @Test
    void wechatBuildsRealCdnAttachmentsAndKeepsVoiceTranscriptionAsText() {
        try (ChannelAdapterRegistry registry = new ChannelAdapterRegistry()) {
            ChannelAdapter adapter = registry.get("wechat");
            ChannelInboundMessage image = adapter.parseWebhook("agent-1", Map.of(
                    "from_user_id", "wx-user",
                    "item_list", List.of(Map.of(
                            "type", 2,
                            "image_item", Map.of(
                                    "aeskey", "30313233343536373839616263646566",
                                    "media", Map.of("encrypt_query_param", "encrypted query/value"))))), Map.of());

            assertEquals("aes-ecb-pkcs7", image.getAttachments().get(0).get("encryption"));
            assertEquals(
                    "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=encrypted+query%2Fvalue",
                    image.getAttachments().get(0).get("url"));

            ChannelInboundMessage voice = adapter.parseWebhook("agent-1", Map.of(
                    "from_user_id", "wx-user",
                    "item_list", List.of(Map.of(
                            "type", 3,
                            "voice_item", Map.of("text_item", Map.of("text", "语音转写内容"))))), Map.of());

            assertEquals("语音转写内容", voice.getContent());
            assertTrue(voice.getAttachments().isEmpty());
        }
    }

    @Test
    void wecomUsesOfficialWebSocketMessageContractAndNeverFakesRunningState() {
        try (ChannelAdapterRegistry registry = new ChannelAdapterRegistry()) {
            ChannelAdapter adapter = registry.get("wecom");
            ChannelHealth health = adapter.health("agent-1", Map.of(
                    "enabled", true,
                    "bot_id", "bot-id",
                    "secret", "secret"));

            assertFalse(health.isRunning());
            assertEquals("configured", health.getStatus());

            ChannelInboundMessage inbound = adapter.parseWebhook("agent-1", Map.of(
                    "msgid", "msg-1",
                    "msgtype", "mixed",
                    "from", Map.of("userid", "user-1"),
                    "chatid", "chat-1",
                    "chattype", "group",
                    "mixed", Map.of("msg_item", List.of(
                            Map.of("msgtype", "text", "text", Map.of("content", "请分析图片")),
                            Map.of("msgtype", "image", "image", Map.of(
                                    "url", "https://example.com/image.enc",
                                    "aeskey", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="))))),
                    Map.of("wecom_req_id", "aibot_msg_callback_1"));

            assertEquals("user-1", inbound.getUserId());
            assertEquals("wecom:group:chat-1", inbound.getSessionId());
            assertEquals("请分析图片", inbound.getContent());
            assertEquals("chat-1", inbound.getReplyTo().getId());
            assertEquals("aibot_msg_callback_1", inbound.getChannelMeta().get("wecom_req_id"));
            assertEquals("aes-256-cbc", inbound.getAttachments().get(0).get("encryption"));
        }
    }

    @Test
    void longConnectionChannelsNeverReportRunningBeforeTheyConnect() {
        try (ChannelAdapterRegistry registry = new ChannelAdapterRegistry()) {
            ChannelHealth dingtalk = registry.get("dingtalk").health("agent-1", Map.of(
                    "enabled", true,
                    "client_id", "ding-app",
                    "client_secret", "secret"));
            ChannelHealth feishu = registry.get("feishu").health("agent-1", Map.of(
                    "enabled", true,
                    "app_id", "cli-app",
                    "app_secret", "secret"));

            assertFalse(dingtalk.isRunning());
            assertEquals("starting", dingtalk.getStatus());
            assertFalse(feishu.isRunning());
            assertEquals("starting", feishu.getStatus());
        }
    }

    @Test
    void dingtalkAndFeishuInboundContractsRemainStable() throws Exception {
        HttpChannelAdapter feishu = new HttpChannelAdapter("feishu", true, true, true);
        ChannelInboundMessage feishuMessage = feishu.parseWebhook("default", Map.of(
                "app_id", "cli_123456",
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("open_id", "ou_x")),
                        "message", Map.of(
                                "chat_id", "oc_1234567890",
                                "chat_type", "group",
                                "content", "{\"text\":\"hi @_all\"}"))), Map.of());
        assertEquals("3456_34567890", feishuMessage.getSessionId());
        assertEquals("hi @_all", feishuMessage.getContent());
        assertEquals("chat_id", feishuMessage.getChannelMeta().get("feishu_receive_id_type"));
        assertTrue(Boolean.TRUE.equals(feishuMessage.getChannelMeta().get("bot_mentioned")));

        AtomicReference<String> posted = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reply", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String previousPrivateOutbound = System.getProperty("melon.outbound.allow-private-for-tests");
        System.setProperty("melon.outbound.allow-private-for-tests", "true");
        try {
            String replyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/reply";
            HttpChannelAdapter dingtalk = new HttpChannelAdapter("dingtalk", true, true, true);
            ChannelInboundMessage inbound = dingtalk.parseWebhook("default", Map.of(
                    "sessionWebhook", replyUrl,
                    "conversationId", "cid_1234567890",
                    "senderStaffId", "uid",
                    "senderNick", "alice",
                    "conversationType", "2",
                    "text", Map.of("content", "question")), Map.of());
            assertEquals("34567890", inbound.getSessionId());
            assertEquals("alice#uid", inbound.getUserId());
            assertEquals("group", inbound.getChannelMeta().get("conversation_type"));

            ChannelOutboundMessage outbound = new ChannelOutboundMessage();
            outbound.setAgentId("default");
            outbound.setChannel("dingtalk");
            outbound.setUserId(inbound.getUserId());
            outbound.setSessionId(inbound.getSessionId());
            outbound.setTo(inbound.getReplyTo());
            outbound.setText("answer");
            outbound.setMeta(Map.of("channel_meta", inbound.getChannelMeta()));
            dingtalk.send(outbound, Map.of("enabled", true)).get();

            assertTrue(posted.get().contains("answer"));
            assertEquals("sent", outbound.getMeta().get("delivery_status"));
        } finally {
            if (previousPrivateOutbound == null) {
                System.clearProperty("melon.outbound.allow-private-for-tests");
            } else {
                System.setProperty("melon.outbound.allow-private-for-tests", previousPrivateOutbound);
            }
            server.stop(0);
        }
    }

    private ConfigManager configManager() {
        ConfigManager manager = new ConfigManager();
        manager.setConfigPath(tempDir.resolve("config.yaml"));
        manager.setHomeDir(tempDir.resolve("home").toString());
        manager.load();
        return manager;
    }
}
