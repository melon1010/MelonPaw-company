package com.melon.app.service;

import com.melon.core.util.JsonUtils;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Verifies signed channel callbacks before they enter the existing channel adapters. */
@Service
public class ChannelWebhookSecurityService {

    private static final long CLOCK_SKEW_SECONDS = 300;
    private static final int MAX_NONCES = 10_000;
    private static final int MAX_REQUESTS_PER_MINUTE = 120;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    private final Map<String, Window> rateWindows = new ConcurrentHashMap<>();

    public void verify(String channel, String agentId, Map<String, Object> config,
                       Map<String, String> headers, Map<String, Object> body) {
        verify(channel, agentId, config, headers, JsonUtils.toJson(body == null ? Map.of() : body), body);
    }

    public void verify(String channel, String agentId, Map<String, Object> config,
                       Map<String, String> headers, String rawBody, Map<String, Object> body) {
        String normalized = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        Map<String, String> lower = lowerHeaders(headers);
        String timestamp = first(lower, "timestamp", "x-lark-request-timestamp", "x-timestamp");
        String nonce = first(lower, "nonce", "x-lark-request-nonce", "x-nonce");
        if ("dingtalk".equals(normalized) && nonce.isBlank()) nonce = timestamp;
        String signature = first(lower, "sign", "signature", "x-lark-signature", "x-melon-signature");
        String secret = switch (normalized) {
            case "dingtalk" -> text(config, "client_secret");
            case "feishu" -> firstNonBlank(text(config, "encrypt_key"), text(config, "verification_token"));
            default -> firstNonBlank(text(config, "secret"), text(config, "access_token"), text(config, "bot_token"));
        };
        if (secret.isBlank() || timestamp.isBlank() || nonce.isBlank() || signature.isBlank()) {
            throw new SecurityException("Signed webhook headers are required");
        }
        long epoch = parseTimestamp(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - epoch) > CLOCK_SKEW_SECONDS) {
            throw new SecurityException("Webhook timestamp is outside the allowed window");
        }
        String payload = rawBody == null ? "" : rawBody;
        String expected = "dingtalk".equals(normalized)
                ? Base64.getEncoder().encodeToString(hmac("HmacSHA256", secret, timestamp + "\n" + secret))
                : "feishu".equals(normalized)
                    ? sha256Hex(timestamp + nonce + secret + payload)
                    : Base64.getEncoder().encodeToString(hmac("HmacSHA256", secret,
                            timestamp + "\n" + nonce + "\n" + payload));
        if (!constantTimeEquals(expected, signature)) {
            throw new SecurityException("Invalid webhook signature");
        }
        String replayKey = normalized + ":" + agentId + ":" + timestamp + ":" + nonce + ":" + signature;
        purge(epoch);
        if (seenNonces.putIfAbsent(replayKey, epoch) != null) {
            throw new SecurityException("Webhook request was already processed");
        }
        enforceRate(agentId, epoch);
    }

    public boolean validChallenge(String channel, Map<String, Object> config, Map<String, Object> body) {
        String token = text(config, "verification_token");
        Object candidate = body == null ? null : body.get("token");
        return !token.isBlank() && candidate != null && constantTimeEquals(token, String.valueOf(candidate));
    }

    private void enforceRate(String agentId, long epoch) {
        String key = agentId == null ? "default" : agentId;
        Window window = rateWindows.compute(key, (ignored, current) -> {
            if (current == null || current.startedAt() / 60 != epoch / 60) return new Window(epoch, 1);
            return new Window(current.startedAt(), current.count() + 1);
        });
        if (window.count() > MAX_REQUESTS_PER_MINUTE) throw new SecurityException("Webhook rate limit exceeded");
    }

    private void purge(long now) {
        if (seenNonces.size() <= MAX_NONCES) return;
        seenNonces.entrySet().removeIf(entry -> now - entry.getValue() > CLOCK_SKEW_SECONDS);
    }

    private long parseTimestamp(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 10_000_000_000L ? parsed / 1000 : parsed;
        } catch (NumberFormatException e) {
            throw new SecurityException("Webhook timestamp is invalid");
        }
    }

    private byte[] hmac(String algorithm, String secret, String value) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Webhook signature algorithm is unavailable", e);
        }
    }

    private String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Map<String, String> lowerHeaders(Map<String, String> headers) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (headers != null) headers.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private String first(Map<String, String> headers, String... names) {
        for (String name : names) {
            String value = headers.get(name);
            if (value != null) return value.trim();
        }
        return "";
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private record Window(long startedAt, int count) {}
}
