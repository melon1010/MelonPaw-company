package com.melon.app.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseAuthServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void bootstrapLoginAndLogoutUseRevocableOpaqueTokens() throws Exception {
        Path database = tempDir.resolve("enterprise.db");
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(database);
        AuthService service = new AuthService(store, Duration.ofHours(1), new SecureRandom());

        EnterprisePrincipal administrator = service.bootstrap(
                "company-admin", "Company Administrator", "StrongPassword123");
        assertTrue(administrator.hasPermission("system:manage"));

        AuthService.AuthResult login = service.login("COMPANY-ADMIN", "StrongPassword123");
        assertTrue(login.success());
        assertTrue(service.authenticateToken(login.token()).isPresent());

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM enterprise_auth_sessions WHERE token_hash = ?")) {
            statement.setString(1, login.token());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "the raw bearer token must never be stored");
            }
        }

        service.logout(login.token());
        assertTrue(service.authenticateToken(login.token()).isEmpty());
    }

    @Test
    void projectSpacesAreVisibleOnlyToMembersOrSystemAdministrators() {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("projects.db"));
        AuthService service = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal administrator = service.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        EnterprisePrincipal employee = service.createUser(
                "employee", "Employee", "EmployeePassword123", true, Set.of("EMPLOYEE"));

        EnterpriseIdentityStore.ProjectSpaceRecord project = store.createProject(
                "customer-care", "Customer Care", "Internal assistant workspace", administrator.id());

        assertEquals(1, store.listProjects(administrator).size());
        assertTrue(store.listProjects(employee).isEmpty());

        store.putProjectMember(project.id(), employee.id(), "MEMBER");
        assertEquals(1, store.listProjects(employee).size());
        assertEquals("MEMBER", store.listProjects(employee).get(0).memberRole());
    }

    @Test
    void protectsPasswordPolicyAndLastAdministrator() {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("policy.db"));
        AuthService service = new AuthService(store, Duration.ofHours(1), new SecureRandom());

        assertThrows(IllegalArgumentException.class,
                () -> service.bootstrap("admin", "Administrator", "short1"));

        EnterprisePrincipal administrator = service.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        assertThrows(IllegalArgumentException.class,
                () -> service.updateUser(administrator.id(), null, false, null));
        assertFalse(service.login("admin", "incorrect-password").success());
    }
}
