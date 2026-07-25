package com.melon.app.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Role and permission catalogue for the enterprise administration console. */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final EnterpriseIdentityStore store;

    public RoleController(EnterpriseIdentityStore store) {
        this.store = store;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('roles:manage') or hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> list() {
        return blocking(() -> ResponseEntity.ok(Map.of(
                "roles", store.listRoles().stream().map(EnterpriseIdentityStore.RoleRecord::toMap).toList(),
                "permissions", EnterprisePermissions.ASSIGNABLE_ROLE_PERMISSIONS.stream().sorted().toList()
        )));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('roles:manage')")
    public Mono<ResponseEntity<?>> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> ResponseEntity.status(201)
                .body(save(text(body, "code"), body, principal).toMap()));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('roles:manage')")
    public Mono<ResponseEntity<?>> update(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> ResponseEntity.ok(save(code, body, principal).toMap()));
    }

    @DeleteMapping("/{code}")
    @PreAuthorize("hasAuthority('roles:manage')")
    public Mono<ResponseEntity<?>> delete(@PathVariable String code) {
        return blocking(() -> {
            try {
                store.deleteCustomRole(code);
                return ResponseEntity.noContent().build();
            } catch (EnterpriseIdentityStore.RoleInUseException e) {
                return ResponseEntity.status(409).body(Map.of(
                        "detail", e.getMessage(),
                        "role_code", e.roleCode(),
                        "user_count", e.userCount()
                ));
            }
        });
    }

    private EnterpriseIdentityStore.RoleRecord save(
            String code, Map<String, Object> body, EnterprisePrincipal principal) {
        Set<String> permissions = stringSet(body == null ? null : body.get("permissions"));
        if (!EnterprisePermissions.ASSIGNABLE_ROLE_PERMISSIONS.containsAll(permissions)) {
            throw new IllegalArgumentException("Custom roles may only contain configurable management permissions");
        }
        if (!principal.hasPermission(SYSTEM_MANAGE)) {
            for (String permission : permissions) {
                if (!principal.hasPermission(permission)) {
                    throw new SecurityException(
                            "Organization administrators cannot grant permission: " + permission);
                }
            }
        }
        return store.saveCustomRole(
                code,
                text(body, "name"),
                text(body, "description"),
                permissions
        );
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    private Set<String> stringSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        }
        return result;
    }

    private String text(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
    }
}
