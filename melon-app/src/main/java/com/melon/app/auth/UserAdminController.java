package com.melon.app.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.melon.app.auth.EnterprisePermissions.SYSTEM_MANAGE;

/** System-administrator API for persistent enterprise users. */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final AuthService authService;
    private final EnterpriseIdentityStore store;
    private final EnterpriseAgentAccessService agentAccessService;

    @Autowired
    public UserAdminController(AuthService authService, EnterpriseIdentityStore store,
                               EnterpriseAgentAccessService agentAccessService) {
        this.authService = authService;
        this.store = store;
        this.agentAccessService = agentAccessService;
    }

    public UserAdminController(AuthService authService, EnterpriseIdentityStore store) {
        this(authService, store, null);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('users:read') or hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> list() {
        return blocking(() -> ResponseEntity.ok(
                store.listUsers().stream().map(EnterprisePrincipal::toMap).toList()));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('users:read') or hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> get(@PathVariable String userId) {
        return blocking(() -> store.findUserById(userId)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(user.toMap()))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            Set<String> roles = stringSet(body.get("roles"));
            if (roles.isEmpty()) {
                roles = Set.of("EMPLOYEE");
            }
            requireNoSystemAdminGrant(principal, roles);
            EnterprisePrincipal created = authService.createUser(
                    text(body, "username"),
                    text(body, "display_name"),
                    text(body, "password"),
                    booleanValue(body.get("enabled"), true),
                    roles
            );
            if (agentAccessService != null && created.hasAnyPermission(
                    EnterprisePermissions.AGENTS_USE,
                    EnterprisePermissions.PERSONAL_WORKSPACE,
                    EnterprisePermissions.PERSONAL_SKILLS,
                    EnterprisePermissions.PERSONAL_CHANNELS,
                    EnterprisePermissions.PERSONAL_AUTOMATIONS)) {
                agentAccessService.ensurePersonalAgent(created);
            }
            return ResponseEntity.status(201).body(created.toMap());
        });
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> update(
            @PathVariable String userId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireCanAdminister(principal, userId);
            String displayName = nullableText(body, "display_name");
            String password = nullableText(body, "password");
            Boolean enabled = body != null && body.containsKey("enabled")
                    ? booleanValue(body.get("enabled"), true)
                    : null;
            return ResponseEntity.ok(authService.updateUser(userId, displayName, enabled, password).toMap());
        });
    }

    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> replaceRoles(
            @PathVariable String userId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireCanAdminister(principal, userId);
            Set<String> roles = stringSet(body == null ? null : body.get("roles"));
            requireNoSystemAdminGrant(principal, roles);
            if (!principal.hasPermission(SYSTEM_MANAGE) && principal.id().equals(userId)) {
                throw new SecurityException("Organization administrators cannot change their own roles");
            }
            return ResponseEntity.ok(store.replaceUserRoles(userId, roles).toMap());
        });
    }

    private void requireCanAdminister(EnterprisePrincipal principal, String userId) {
        if (principal.hasPermission(SYSTEM_MANAGE)) {
            return;
        }
        EnterprisePrincipal target = store.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (target.hasRole("SYSTEM_ADMIN")) {
            throw new SecurityException("Only a system administrator can modify a system administrator");
        }
    }

    private void requireNoSystemAdminGrant(EnterprisePrincipal principal, Set<String> roles) {
        if (!principal.hasPermission(SYSTEM_MANAGE) && roles.contains("SYSTEM_ADMIN")) {
            throw new SecurityException("Only a system administrator can grant the SYSTEM_ADMIN role");
        }
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    private Set<String> stringSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim().toUpperCase());
                }
            }
        }
        return result;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private String nullableText(Map<String, Object> body, String key) {
        return body == null || !body.containsKey(key) ? null : text(body, key);
    }

    private String text(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
    }
}
