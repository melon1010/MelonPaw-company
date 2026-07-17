package com.melon.app.controller;

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

    public SecretManagementController(SecretRotationService secretRotationService) {
        this.secretRotationService = secretRotationService;
    }

    @PostMapping("/rotate")
    @PreAuthorize("hasAuthority('system:manage')")
    public Mono<ResponseEntity<?>> rotate() {
        return Mono.<ResponseEntity<?>>fromCallable(
                        () -> ResponseEntity.ok(secretRotationService.reencryptAll()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
