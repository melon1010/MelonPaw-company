package com.melon.app.controller;

import com.melon.app.task.EnterpriseTaskService;
import com.melon.app.task.EnterpriseQuotaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> security(SecurityException e) {
        return ResponseEntity.status(403).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(EnterpriseTaskService.AgentMutationInProgressException.class)
    public ResponseEntity<Map<String, Object>> agentMutationConflict(
            EnterpriseTaskService.AgentMutationInProgressException e) {
        return ResponseEntity.status(409).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(EnterpriseTaskService.ActiveTaskConflictException.class)
    public ResponseEntity<Map<String, Object>> activeTaskConflict(
            EnterpriseTaskService.ActiveTaskConflictException e) {
        return ResponseEntity.status(409).body(Map.of(
                "detail", e.getMessage(),
                "code", "ACTIVE_TASK",
                "task_id", e.taskId(),
                "run_id", e.runId(),
                "session_id", e.sessionId(),
                "status", e.status(),
                "reconnect", true));
    }

    @ExceptionHandler(EnterpriseQuotaService.QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> quotaExceeded(
            EnterpriseQuotaService.QuotaExceededException e) {
        return ResponseEntity.status(429).body(Map.of(
                "detail", e.getMessage(),
                "code", "quota_exceeded",
                "scope_type", e.scopeType(),
                "limit_type", e.limitType()));
    }
}
