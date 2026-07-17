package com.melon.app.controller;

import com.melon.app.service.SandboxHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** Deployment preflight for the configured Agent execution sandbox. */
@RestController
@RequestMapping("/api/agent/sandbox")
public class SandboxHealthController {

    private final SandboxHealthService healthService;

    public SandboxHealthController(SandboxHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        return Mono.fromCallable(() -> ResponseEntity.ok(healthService.inspect()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
