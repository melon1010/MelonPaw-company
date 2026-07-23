package com.melon.app.controller;

import com.melon.app.web.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void returnsStableBadRequestContractAndRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Request-ID", "request-123")
                        .build());

        var response = handler.badRequest(new IllegalArgumentException("invalid value"), exchange);
        ApiErrorResponse body = response.getBody();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_argument", body.getCode());
        assertEquals("invalid value", body.getDetail());
        assertEquals("request-123", body.getRequestId());
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());

        var response = handler.unexpected(new IllegalStateException("database password leaked"), exchange);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("internal_error", response.getBody().getCode());
        assertEquals("Internal server error", response.getBody().getDetail());
    }
}
