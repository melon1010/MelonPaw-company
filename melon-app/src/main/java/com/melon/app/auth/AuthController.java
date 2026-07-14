package com.melon.app.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/** Enterprise authentication endpoints used by the web console and CLI clients. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/bootstrap")
    public Mono<ResponseEntity<?>> bootstrap(@RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> {
            if (authService.hasUsers()) {
                return ResponseEntity.status(409).body(Map.of(
                        "status", "error",
                        "detail", "The initial administrator has already been created"
                ));
            }
            String username = text(body, "username");
            String password = text(body, "password");
            String displayName = text(body, "display_name");
            authService.bootstrap(username, displayName, password);
            return ResponseEntity.ok(authService.login(username, password).toMap());
        });
    }

    /** Compatibility alias. Registration is only allowed while the user table is empty. */
    @PostMapping("/register")
    public Mono<ResponseEntity<?>> register(@RequestBody(required = false) Map<String, Object> body) {
        return bootstrap(body);
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<?>> login(@RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> {
            if (!authService.hasUsers()) {
                return ResponseEntity.status(428).body(Map.of(
                        "status", "error",
                        "bootstrap_required", true,
                        "detail", "Create the initial administrator first"
                ));
            }
            AuthService.AuthResult result = authService.login(text(body, "username"), text(body, "password"));
            return result.success()
                    ? ResponseEntity.ok(result.toMap())
                    : ResponseEntity.status(401).body(result.toMap());
        });
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<?>> status(@AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            boolean hasUsers = authService.hasUsers();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", true);
            result.put("has_users", hasUsers);
            result.put("bootstrap_required", !hasUsers);
            result.put("authenticated", principal != null);
            result.put("auth_disabled", false);
            result.put("user", principal == null ? null : principal.toMap());
            return ResponseEntity.ok(result);
        });
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<?>> me(@AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.just(ResponseEntity.ok(principal.toMap()));
    }

    @GetMapping("/verify")
    public Mono<ResponseEntity<?>> verifyGet(@AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "valid", true,
                "status", "valid",
                "user", principal.toMap()
        )));
    }

    @PostMapping("/verify")
    public Mono<ResponseEntity<?>> verify(@AuthenticationPrincipal EnterprisePrincipal principal) {
        return verifyGet(principal);
    }

    @PutMapping("/profile")
    public Mono<ResponseEntity<?>> updateProfile(
            @AuthenticationPrincipal EnterprisePrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> {
            String displayName = firstText(body, "display_name", "new_display_name");
            String currentPassword = firstText(body, "current_password", "currentPassword");
            String newPassword = firstText(body, "new_password", "newPassword");
            EnterprisePrincipal updated = authService.updateOwnProfile(
                    principal.id(), currentPassword, displayName.isBlank() ? null : displayName,
                    newPassword.isBlank() ? null : newPassword);
            return ResponseEntity.ok(updated.toMap());
        });
    }

    @PostMapping("/update-profile")
    public Mono<ResponseEntity<?>> updateProfileCompatibility(
            @AuthenticationPrincipal EnterprisePrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        return updateProfile(principal, body);
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<?>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return blocking(() -> {
            authService.logout(bearerToken(authorization));
            return ResponseEntity.ok(Map.of("status", "logged_out"));
        });
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    private String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : null;
    }

    private String firstText(Map<String, Object> body, String first, String second) {
        String value = text(body, first);
        return value.isBlank() ? text(body, second) : value;
    }

    private String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return String.valueOf(body.get(key)).trim();
    }
}
