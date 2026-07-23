package com.melon.app.web;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Stable API error contract. {@code detail} remains for console compatibility. */
public final class ApiErrorResponse {

    private static final Set<String> RESERVED = Set.of("code", "detail", "request_id", "timestamp");

    private final String code;
    private final String detail;
    private final String requestId;
    private final String timestamp;
    private final Map<String, Object> attributes;

    private ApiErrorResponse(String code, String detail, String requestId, Map<String, Object> attributes) {
        this.code = value(code, "internal_error");
        this.detail = value(detail, "Request failed");
        this.requestId = value(requestId, "unknown");
        this.timestamp = Instant.now().toString();
        this.attributes = clean(attributes);
    }

    public static ApiErrorResponse of(String code, String detail, String requestId) {
        return new ApiErrorResponse(code, detail, requestId, Map.of());
    }

    public static ApiErrorResponse of(
            String code, String detail, String requestId, Map<String, Object> attributes) {
        return new ApiErrorResponse(code, detail, requestId, attributes);
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    @JsonProperty("request_id")
    public String getRequestId() {
        return requestId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    @JsonAnyGetter
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    private static Map<String, Object> clean(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null && !RESERVED.contains(key)) result.put(key, value);
        });
        return Map.copyOf(result);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
