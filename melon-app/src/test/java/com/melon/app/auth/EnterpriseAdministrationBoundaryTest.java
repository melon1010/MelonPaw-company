package com.melon.app.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnterpriseAdministrationBoundaryTest {

    @TempDir
    Path tempDir;

    private EnterpriseIdentityStore store;
    private AuthService authService;
    private EnterprisePrincipal systemAdministrator;
    private EnterprisePrincipal organizationAdministrator;

    @BeforeEach
    void setUp() {
        store = new EnterpriseIdentityStore(tempDir.resolve("enterprise.db"));
        authService = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        systemAdministrator = authService.bootstrap(
                "system-admin", "System Administrator", "StrongPassword123");
        organizationAdministrator = authService.createUser(
                "org-admin", "Organization Administrator", "OrgAdminPassword123", true,
                Set.of("ORG_ADMIN"));
    }

    @Test
    void organizationAdministratorCannotGrantOrModifySystemAdministrator() {
        UserAdminController controller = new UserAdminController(authService, store);

        assertThrows(SecurityException.class, () -> controller.create(Map.of(
                "username", "second-system-admin",
                "display_name", "Second System Administrator",
                "password", "SecondAdminPassword123",
                "roles", List.of("SYSTEM_ADMIN")
        ), organizationAdministrator).block());

        assertThrows(SecurityException.class, () -> controller.update(
                systemAdministrator.id(),
                Map.of("password", "CompromisedPassword123"),
                organizationAdministrator
        ).block());

        assertThrows(SecurityException.class, () -> controller.replaceRoles(
                organizationAdministrator.id(),
                Map.of("roles", List.of("SYSTEM_ADMIN")),
                organizationAdministrator
        ).block());
    }

    @Test
    void organizationAdministratorCannotCreateARoleWithPermissionsTheyDoNotHold() {
        RoleController controller = new RoleController(store);

        assertThrows(SecurityException.class, () -> controller.create(Map.of(
                "code", "HOST_ADMIN",
                "name", "Host Administrator",
                "description", "must not be creatable by organization administrators",
                "permissions", List.of("system:manage")
        ), organizationAdministrator).block());

        var response = controller.create(Map.of(
                "code", "PROJECT_AUDITOR",
                "name", "Project Auditor",
                "description", "read projects and audit records",
                "permissions", List.of("projects:read", "audit:read")
        ), organizationAdministrator).block();
        assertEquals(201, response.getStatusCode().value());
    }
}
