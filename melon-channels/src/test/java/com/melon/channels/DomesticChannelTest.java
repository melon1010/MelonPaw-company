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
                    "mattermost", "mqtt", "voice", "sip", "custom-webhook")) {
                assertFalse(ChannelTypes.supported(removed));
                assertThrows(IllegalArgumentException.class, () -> registry.get(removed));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new HttpChannelAdapter("telegram", false, true, true));
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
            assertTrue(listed.get("onebot").containsKey("access_token"));
            assertFalse(listed.get("onebot").containsKey("ws_port"));
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
