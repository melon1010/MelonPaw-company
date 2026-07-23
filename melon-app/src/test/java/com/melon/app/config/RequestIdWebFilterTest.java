package com.melon.app.config;

import com.melon.app.web.RequestIdSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RequestIdWebFilterTest {

    @Test
    void preservesSafeRequestIdAndAddsItToTheResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RequestIdSupport.HEADER, "trace:123")
                        .build());

        new RequestIdWebFilter().filter(exchange, ignored -> Mono.empty()).block();

        assertEquals("trace:123", exchange.getAttribute(RequestIdSupport.ATTRIBUTE));
        assertEquals("trace:123", exchange.getResponse().getHeaders().getFirst(RequestIdSupport.HEADER));
    }

    @Test
    void replacesUnsafeRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(RequestIdSupport.HEADER, "invalid request id")
                        .build());

        new RequestIdWebFilter().filter(exchange, ignored -> Mono.empty()).block();

        String generated = exchange.getAttribute(RequestIdSupport.ATTRIBUTE);
        assertNotNull(generated);
        assertEquals(generated, exchange.getResponse().getHeaders().getFirst(RequestIdSupport.HEADER));
    }
}
