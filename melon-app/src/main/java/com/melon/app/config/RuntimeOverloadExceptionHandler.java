package com.melon.app.config;

import com.melon.app.web.ApiErrorResponse;
import com.melon.app.web.RequestIdSupport;
import com.melon.core.util.JsonUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;

/** Converts bounded runtime executor saturation into the stable API error contract. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RuntimeOverloadExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith("/api/") || !isExecutorRejection(error)
                || exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        String requestId = RequestIdSupport.resolve(exchange);
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(RequestIdSupport.HEADER, requestId);
        String json = JsonUtils.toJson(ApiErrorResponse.of(
                "runtime_overloaded",
                "The runtime is busy; retry after the current work drains",
                requestId));
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }

    private boolean isExecutorRejection(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RejectedExecutionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
