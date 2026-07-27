package com.melon.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeOverloadExceptionHandlerTest {

    @Test
    void apiExecutorRejectionUsesStableServiceUnavailableResponse() {
        RuntimeOverloadExceptionHandler handler = new RuntimeOverloadExceptionHandler();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/health").build());

        handler.handle(exchange, new RejectedExecutionException("full"))
                .block(Duration.ofSeconds(1));

        assertEquals(503, exchange.getResponse().getStatusCode().value());
        String body = exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(1));
        assertNotNull(body);
        assertTrue(body.contains("\"code\":\"runtime_overloaded\""), () -> body);
        assertNotNull(exchange.getResponse().getHeaders().getFirst("X-Request-ID"));
    }

    @Test
    void nonApiExecutorRejectionIsNotRewritten() {
        RuntimeOverloadExceptionHandler handler = new RuntimeOverloadExceptionHandler();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/console/index.html").build());

        assertThrows(RejectedExecutionException.class,
                () -> handler.handle(exchange, new RejectedExecutionException("full"))
                        .block(Duration.ofSeconds(1)));
    }
}
