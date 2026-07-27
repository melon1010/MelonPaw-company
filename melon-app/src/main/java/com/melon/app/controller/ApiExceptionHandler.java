package com.melon.app.controller;

import com.melon.app.task.EnterpriseQuotaService;
import com.melon.app.task.EnterpriseTaskService;
import com.melon.app.web.ApiErrorResponse;
import com.melon.app.web.RequestIdSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiErrorResponse> security(SecurityException error, ServerWebExchange exchange) {
        return response(HttpStatus.FORBIDDEN, "forbidden", detail(error, "Permission denied"), exchange);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(
            IllegalArgumentException error, ServerWebExchange exchange) {
        return response(HttpStatus.BAD_REQUEST, "invalid_argument", detail(error, "Invalid request"), exchange);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiErrorResponse> malformedRequest(
            ServerWebInputException error, ServerWebExchange exchange) {
        return response(HttpStatus.BAD_REQUEST, "malformed_request",
                detail(error, "Malformed request body or parameters"), exchange);
    }

    @ExceptionHandler(EnterpriseTaskService.AgentMutationInProgressException.class)
    public ResponseEntity<ApiErrorResponse> agentMutationConflict(
            EnterpriseTaskService.AgentMutationInProgressException error, ServerWebExchange exchange) {
        return response(HttpStatus.CONFLICT, "agent_mutation_in_progress", detail(error, "Agent is busy"), exchange);
    }

    @ExceptionHandler(EnterpriseTaskService.ActiveTaskConflictException.class)
    public ResponseEntity<ApiErrorResponse> activeTaskConflict(
            EnterpriseTaskService.ActiveTaskConflictException error, ServerWebExchange exchange) {
        return response(HttpStatus.CONFLICT, "ACTIVE_TASK", detail(error, "An active task already exists"), exchange,
                Map.of(
                        "task_id", error.taskId(),
                        "run_id", error.runId(),
                        "session_id", error.sessionId(),
                        "status", error.status(),
                        "reconnect", true));
    }

    @ExceptionHandler(EnterpriseQuotaService.QuotaExceededException.class)
    public ResponseEntity<ApiErrorResponse> quotaExceeded(
            EnterpriseQuotaService.QuotaExceededException error, ServerWebExchange exchange) {
        return response(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", detail(error, "Quota exceeded"), exchange,
                Map.of("scope_type", error.scopeType(), "limit_type", error.limitType()));
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<ApiErrorResponse> overloaded(
            RejectedExecutionException error, ServerWebExchange exchange) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "runtime_overloaded",
                "The runtime is busy; retry after the current work drains", exchange);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> responseStatus(
            ResponseStatusException error, ServerWebExchange exchange) {
        HttpStatusCode status = error.getStatusCode();
        String reason = error.getReason();
        return response(status, code(status),
                reason == null || reason.isBlank() ? status.toString() : reason, exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception error, ServerWebExchange exchange) {
        String requestId = RequestIdSupport.resolve(exchange);
        log.error("Unhandled API exception: request_id={}, method={}, path={}", requestId,
                exchange.getRequest().getMethod(), exchange.getRequest().getPath(), error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("internal_error", "Internal server error", requestId));
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status, String code, String detail, ServerWebExchange exchange) {
        return response(status, code, detail, exchange, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status, String code, String detail, ServerWebExchange exchange,
            Map<String, Object> attributes) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(
                code, detail, RequestIdSupport.resolve(exchange), attributes));
    }

    private static String detail(Throwable error, String fallback) {
        return error.getMessage() == null || error.getMessage().isBlank() ? fallback : error.getMessage();
    }

    private static String code(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 409 -> "conflict";
            case 413 -> "payload_too_large";
            case 429 -> "too_many_requests";
            default -> status.is4xxClientError() ? "client_error" : "server_error";
        };
    }
}
