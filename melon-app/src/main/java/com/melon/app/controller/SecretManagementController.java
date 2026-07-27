package com.melon.app.controller;

import com.melon.app.config.RuntimeExecutors;
import com.melon.app.service.SecretRotationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** System-only secret lifecycle operations. */
@RestController
@RequestMapping("/api/config/security/secrets")
public class SecretManagementController {

    private final SecretRotationService secretRotationService;
    private final RuntimeExecutors executors;

    public SecretManagementController(SecretRotationService secretRotationService) {
        this(secretRotationService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SecretManagementController(SecretRotationService secretRotationService, RuntimeExecutors executors) {
        this.secretRotationService = secretRotationService;
        this.executors = executors;
    }

    @PostMapping("/rotate")
    @PreAuthorize("hasAuthority('security:manage')")
    public Mono<ResponseEntity<?>> rotate() {
        return Mono.<ResponseEntity<?>>fromCallable(
                        () -> ResponseEntity.ok(secretRotationService.reencryptAll()))
                .subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }
}
