package com.melon.app.auth;

import com.melon.core.config.ConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Enterprise authentication endpoints used by the Web console. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;
    private final String bootstrapToken;
    private final Path generatedBootstrapTokenFile;

    @Autowired
    public AuthController(AuthService authService,
                          @Value("${melon.enterprise.auth.bootstrap-token:}") String bootstrapToken,
                          @Value("${melon.enterprise.auth.generate-bootstrap-token:false}")
                          boolean generateBootstrapToken,
                          ConfigManager configManager) {
        this.authService = authService;
        String configured = bootstrapToken == null ? "" : bootstrapToken.trim();
        if (configured.isBlank() && generateBootstrapToken && !authService.hasUsers()) {
            this.generatedBootstrapTokenFile = configManager.resolveHomeDir().resolve("bootstrap.token");
            this.bootstrapToken = loadOrCreateBootstrapToken(generatedBootstrapTokenFile);
            log.warn("Initial administrator bootstrap token written to {}", generatedBootstrapTokenFile);
        } else {
            this.generatedBootstrapTokenFile = null;
            this.bootstrapToken = configured;
        }
    }

    AuthController(AuthService authService) {
        this(authService, "");
    }

    AuthController(AuthService authService, String bootstrapToken) {
        this.authService = authService;
        this.bootstrapToken = bootstrapToken == null ? "" : bootstrapToken.trim();
        this.generatedBootstrapTokenFile = null;
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
            requireBootstrapToken(body);
            String username = text(body, "username");
            String password = text(body, "password");
            String displayName = text(body, "display_name");
            authService.bootstrap(username, displayName, password);
            removeGeneratedBootstrapToken();
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
            result.put("bootstrap_token_required", !hasUsers && !bootstrapToken.isBlank());
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

    private void requireBootstrapToken(Map<String, Object> body) {
        if (bootstrapToken.isBlank()) return;
        String supplied = text(body, "bootstrap_token");
        boolean matches = MessageDigest.isEqual(
                bootstrapToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new SecurityException("A valid bootstrap token is required");
        }
    }

    private String loadOrCreateBootstrapToken(Path tokenFile) {
        try {
            Files.createDirectories(tokenFile.getParent());
            if (Files.isRegularFile(tokenFile)) {
                String existing = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (!existing.isBlank()) return existing;
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Files.writeString(tokenFile, generated, StandardCharsets.UTF_8);
            if (Files.getFileStore(tokenFile).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(tokenFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize bootstrap token file: " + tokenFile, e);
        }
    }

    private void removeGeneratedBootstrapToken() {
        if (generatedBootstrapTokenFile == null) return;
        try {
            Files.deleteIfExists(generatedBootstrapTokenFile);
        } catch (Exception e) {
            log.warn("Failed to remove consumed bootstrap token file {}", generatedBootstrapTokenFile, e);
        }
    }
}
