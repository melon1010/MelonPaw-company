package com.melon.app.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Role and permission catalogue for the enterprise administration console. */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final EnterpriseIdentityStore store;

    public RoleController(EnterpriseIdentityStore store) {
        this.store = store;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('roles:read') or hasAuthority('roles:manage')")
    public Mono<ResponseEntity<?>> list() {
        return blocking(() -> ResponseEntity.ok(Map.of(
                "roles", store.listRoles().stream().map(EnterpriseIdentityStore.RoleRecord::toMap).toList(),
                "permissions", store.listPermissions()
        )));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('roles:manage')")
    public Mono<ResponseEntity<?>> create(@RequestBody Map<String, Object> body) {
        return blocking(() -> ResponseEntity.status(201).body(save(text(body, "code"), body).toMap()));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('roles:manage')")
    public Mono<ResponseEntity<?>> update(
            @PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(save(code, body).toMap()));
    }

    private EnterpriseIdentityStore.RoleRecord save(String code, Map<String, Object> body) {
        return store.saveCustomRole(
                code,
                text(body, "name"),
                text(body, "description"),
                stringSet(body == null ? null : body.get("permissions"))
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
