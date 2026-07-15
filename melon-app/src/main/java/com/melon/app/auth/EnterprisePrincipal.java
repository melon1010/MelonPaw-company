package com.melon.app.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authenticated enterprise user. The user id comes exclusively from the verified access token.
 */
public record EnterprisePrincipal(
        String id,
        String username,
        String displayName,
        boolean enabled,
        Set<String> roles,
        Set<String> permissions,
        String createdAt,
        String updatedAt
) {

    public EnterprisePrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String permission) {
        if (EnterprisePermissions.DATA_BREAKGLASS.equals(permission)) {
            return permissions.contains(permission);
        }
        return hasRole("SYSTEM_ADMIN") || permissions.contains(permission);
    }

    public boolean hasAnyPermission(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (hasPermission(value)) {
                return true;
            }
        }
        return false;
    }

    public List<GrantedAuthority> authorities() {
        Set<String> values = new LinkedHashSet<>();
        roles.forEach(role -> values.add("ROLE_" + role));
        values.addAll(permissions);
        return values.stream().map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "username", username,
                "display_name", displayName == null ? "" : displayName,
                "enabled", enabled,
                "roles", roles.stream().sorted().toList(),
                "permissions", permissions.stream().sorted().toList(),
                "created_at", createdAt == null ? "" : createdAt,
                "updated_at", updatedAt == null ? "" : updatedAt
        );
    }
}
