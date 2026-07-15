package com.melon.channels;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpChannelAdapterSelfCheck {

    private HttpChannelAdapterSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        HttpChannelAdapter feishu = new HttpChannelAdapter("feishu", false, true, true);
        ChannelHealth badFeishu = feishu.health("default", Map.of("enabled", true));
        if (!"misconfigured".equals(badFeishu.getStatus()) || badFeishu.isAvailable()) {
            throw new AssertionError("feishu without app credentials should be misconfigured");
        }
        ChannelInboundMessage feishuMessage = feishu.parseWebhook("default", Map.of(
                "app_id", "cli_123456",
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("open_id", "ou_x")),
                        "message", Map.of("chat_id", "oc_1234567890", "chat_type", "group",
                                "content", "{\"text\":\"hi @_all\"}")
                )
        ), Map.of());
        if (!"3456_34567890".equals(feishuMessage.getSessionId())
                || !"hi @_all".equals(feishuMessage.getContent())
                || !Boolean.TRUE.equals(feishuMessage.getChannelMeta().get("bot_mentioned"))
                || !"chat_id".equals(feishuMessage.getChannelMeta().get("feishu_receive_id_type"))) {
            throw new AssertionError("feishu payload was not parsed: " + feishuMessage.getChannelMeta());
        }

        AtomicReference<String> posted = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reply", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String reply = "http://127.0.0.1:" + port + "/reply";
            HttpChannelAdapter dingtalk = new HttpChannelAdapter("dingtalk", false, true, true);
            ChannelInboundMessage inbound = dingtalk.parseWebhook("default", Map.of(
                    "sessionWebhook", reply,
                    "conversationId", "cid_1234567890",
                    "senderStaffId", "uid",
                    "senderNick", "alice",
                    "conversationType", "2",
                    "text", Map.of("content", "question")
            ), Map.of());
            if (!"34567890".equals(inbound.getSessionId())
                    || !"alice#uid".equals(inbound.getUserId())
                    || !"group".equals(inbound.getChannelMeta().get("conversation_type"))) {
                throw new AssertionError("dingtalk inbound shape drifted: " + inbound.getChannelMeta());
            }
            ChannelOutboundMessage out = new ChannelOutboundMessage();
            out.setAgentId("default");
            out.setChannel("dingtalk");
            out.setUserId(inbound.getUserId());
            out.setSessionId(inbound.getSessionId());
            out.setTo(inbound.getReplyTo());
            out.setText("answer");
            out.setMeta(Map.of("channel_meta", inbound.getChannelMeta()));
            dingtalk.send(out, Map.of("enabled", true)).get();
            if (!posted.get().contains("answer") || !"sent".equals(out.getMeta().get("delivery_status"))) {
                throw new AssertionError("dingtalk sessionWebhook was not posted: " + out.getMeta());
            }
        } finally {
            server.stop(0);
        }
    }
}
