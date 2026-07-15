package com.melon.app.channel;

import com.melon.channels.ChannelAdapter;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelManager;
import com.melon.channels.OneBotChannelAdapter;
import com.melon.core.util.JsonUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class OneBotWebSocketHandler implements WebSocketHandler {

    private final ChannelAdapterRegistry registry;
    private final ChannelManager channelManager;
    private final ChannelConfigService channelConfigService;

    public OneBotWebSocketHandler(ChannelAdapterRegistry registry,
                                  ChannelManager channelManager,
                                  ChannelConfigService channelConfigService) {
        this.registry = registry;
        this.channelManager = channelManager;
        this.channelConfigService = channelConfigService;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String agentId = agentId(session);
        ChannelAdapter adapter = registry.get("onebot");
        if (!(adapter instanceof OneBotChannelAdapter oneBot)) {
            return session.close();
        }
        if (!authorized(agentId, accessToken(session))) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        String connectionId = UUID.randomUUID().toString();
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        oneBot.attach(agentId, new OneBotChannelAdapter.OneBotConnection() {
            @Override
            public CompletableFuture<Void> send(String payload) {
                Sinks.EmitResult result = outbound.tryEmitNext(payload);
                return result.isSuccess()
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException(result.name()));
            }

            @Override
            public String id() {
                return connectionId;
            }
        });

        Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> handlePayload(agentId, payload))
                .then()
                .doFinally(signal -> {
                    oneBot.detach(agentId, connectionId);
                    outbound.tryEmitComplete();
                });
        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
        return send.and(receive);
    }

    private Mono<Void> handlePayload(String agentId, String payload) {
        Map<String, Object> body = JsonUtils.fromJson(payload, Map.class);
        if (body == null) body = new LinkedHashMap<>();
        String postType = String.valueOf(body.getOrDefault("post_type", ""));
        if (!postType.isBlank() && !"message".equals(postType)) {
            return Mono.empty();
        }
        return Mono.fromFuture(channelManager.handleWebhook(agentId, "onebot", body, Map.of())).then();
    }

    private String agentId(WebSocketSession session) {
        String value = UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri())
                .build().getQueryParams().getFirst("agent_id");
        if (!StringUtils.hasText(value)) {
            value = session.getHandshakeInfo().getHeaders().getFirst("X-Agent-Id");
        }
        if (value == null || value.isBlank()) return "default";
        String normalized = value.trim();
        return normalized.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                ? normalized
                : "default";
    }

    boolean authorized(String agentId, String suppliedToken) {
        Map<String, Object> config = channelConfigService.runtimeConfig(agentId, "onebot");
        if (!Boolean.TRUE.equals(config.get("enabled"))) {
            return false;
        }
        String expected = String.valueOf(config.getOrDefault("access_token", ""));
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(suppliedToken)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }

    private String accessToken(WebSocketSession session) {
        HttpHeaders headers = session.getHandshakeInfo().getHeaders();
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        String token = headers.getFirst("X-Access-Token");
        return token != null ? token.trim() : "";
    }
}
