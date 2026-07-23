package com.melon.app.web;

import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;
import java.util.regex.Pattern;

public final class RequestIdSupport {

    public static final String HEADER = "X-Request-ID";
    public static final String ATTRIBUTE = RequestIdSupport.class.getName() + ".requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private RequestIdSupport() {
    }

    public static String resolve(ServerWebExchange exchange) {
        Object existing = exchange.getAttribute(ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) return value;
        String supplied = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = supplied != null && SAFE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        return requestId;
    }
}
