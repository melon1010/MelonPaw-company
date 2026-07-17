package com.melon.app.controller;

import com.melon.app.service.EnterpriseAuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only enterprise management audit API. */
@RestController
@RequestMapping("/api/audit/management")
public class EnterpriseAuditController {

    private final EnterpriseAuditService auditService;

    public EnterpriseAuditController(EnterpriseAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit:read') or hasAuthority('system:manage')")
    public Mono<ResponseEntity<?>> list(
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(
                        auditService.query(actorUserId, action, targetType, result, limit, offset)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAuthority('audit:read') or hasAuthority('system:manage')")
    public Mono<ResponseEntity<?>> verify() {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(auditService.verifyChain()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
