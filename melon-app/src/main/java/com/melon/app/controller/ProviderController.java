package com.melon.app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Explicit capability response for provider OAuth, which is not implemented by the Java runtime.
 * MCP OAuth is a separate, supported feature.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    @PostMapping("/{providerId}/oauth/start")
    public Mono<ResponseEntity<?>> startOAuth(@PathVariable String providerId) {
        return Mono.just(unsupported(providerId));
    }

    @GetMapping("/{providerId}/oauth/status")
    public Mono<ResponseEntity<?>> oauthStatus(@PathVariable String providerId,
                                               @RequestParam(required = false) String state) {
        return Mono.just(unsupported(providerId));
    }

    private ResponseEntity<?> unsupported(String providerId) {
        return ResponseEntity.status(501).body(Map.of(
                "detail", "Provider OAuth is not supported; configure an API key instead",
                "status", "unsupported",
                "provider_id", providerId
        ));
    }
}
