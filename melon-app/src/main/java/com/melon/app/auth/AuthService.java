package com.melon.app.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent multi-user authentication service.
 *
 * <p>Passwords use BCrypt. Access tokens are opaque random values; only their SHA-256 digest is
 * stored, which prevents a copied database from immediately becoming a source of live tokens.</p>
 */
@Service
public class AuthService {

    private static final int BCRYPT_STRENGTH = 12;
    private static final int TOKEN_BYTES = 32;
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final EnterpriseIdentityStore store;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;
    private final Duration tokenTtl;

    @Autowired
    public AuthService(
            EnterpriseIdentityStore store,
            @Value("${melon.enterprise.auth.token-ttl-hours:12}") long tokenTtlHours) {
        this(store, Duration.ofHours(Math.max(1L, tokenTtlHours)), new SecureRandom());
    }

    AuthService(EnterpriseIdentityStore store, Duration tokenTtl, SecureRandom secureRandom) {
        this.store = store;
        this.passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH, secureRandom);
        this.secureRandom = secureRandom;
        this.tokenTtl = tokenTtl;
    }

    public boolean hasUsers() {
        return store.countUsers() > 0;
    }

    public EnterprisePrincipal bootstrap(String username, String displayName, String password) {
        validatePassword(password);
        return store.bootstrapAdmin(username, displayName, passwordEncoder.encode(password));
    }

    public AuthResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            return AuthResult.failure("Invalid username or password");
        }
        Optional<EnterpriseIdentityStore.StoredUser> found = store.findUserByUsername(username);
        if (found.isEmpty()) {
            consumePasswordWork(password);
            return AuthResult.failure("Invalid username or password");
        }
        EnterpriseIdentityStore.StoredUser user = found.get();
        if (!user.enabled() || !passwordEncoder.matches(password, user.passwordHash())) {
            return AuthResult.failure("Invalid username or password");
        }
        return issueToken(user.toPrincipal());
    }

    public Optional<EnterprisePrincipal> authenticateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return store.findPrincipalByTokenHash(tokenHash(token));
    }

    public boolean verifyToken(String token) {
        return authenticateToken(token).isPresent();
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            store.revokeSession(tokenHash(token));
        }
    }

    public boolean verifyPassword(String userId, String password) {
        if (password == null) {
            return false;
        }
        return store.findUserById(userId)
                .flatMap(user -> store.findUserByUsername(user.username()))
                .map(stored -> passwordEncoder.matches(password, stored.passwordHash()))
                .orElse(false);
    }

    public EnterprisePrincipal createUser(
            String username,
            String displayName,
            String password,
            boolean enabled,
            Set<String> roles) {
        validatePassword(password);
        return store.createUser(username, displayName, passwordEncoder.encode(password), enabled, roles);
    }

    public EnterprisePrincipal updateUser(
            String userId, String displayName, Boolean enabled, String newPassword) {
        String passwordHash = null;
        if (newPassword != null && !newPassword.isBlank()) {
            validatePassword(newPassword);
            passwordHash = passwordEncoder.encode(newPassword);
        }
        return store.updateUser(userId, displayName, enabled, passwordHash);
    }

    public EnterprisePrincipal updateOwnProfile(
            String userId, String currentPassword, String displayName, String newPassword) {
        if (!verifyPassword(userId, currentPassword)) {
            throw new SecurityException("Current password is incorrect");
        }
        String passwordHash = null;
        if (newPassword != null && !newPassword.isBlank()) {
            validatePassword(newPassword);
            passwordHash = passwordEncoder.encode(newPassword);
        }
        return store.updateOwnProfile(userId, displayName, passwordHash);
    }

    public EnterpriseIdentityStore store() {
        return store;
    }

    private AuthResult issueToken(EnterprisePrincipal principal) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = Instant.now().plus(tokenTtl);
        store.createSession(tokenHash(token), principal.id(), expiresAt);
        return AuthResult.success(token, expiresAt, principal);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must contain at least 12 characters");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("Password must contain at least one letter and one number");
        }
    }

    private void consumePasswordWork(String password) {
        passwordEncoder.matches(password, "$2a$12$Jq5Yk7pP9W0c4DLcJxFqSuu/SGglrMepbp73WQ48mKnuZQUn7.PS6");
    }

    private String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record AuthResult(
            boolean success,
            String token,
            Instant expiresAt,
            EnterprisePrincipal principal,
            String error
    ) {
        static AuthResult success(String token, Instant expiresAt, EnterprisePrincipal principal) {
            return new AuthResult(true, token, expiresAt, principal, null);
        }

        static AuthResult failure(String error) {
            return new AuthResult(false, null, null, null, error);
        }

        public Map<String, Object> toMap() {
            if (!success) {
                return Map.of("status", "error", "error", error, "detail", error);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("token", token);
            result.put("token_type", "Bearer");
            result.put("expires_at", expiresAt.toString());
            result.put("subject", principal.username());
            result.put("username", principal.username());
            result.put("user", principal.toMap());
            return result;
        }
    }
}
