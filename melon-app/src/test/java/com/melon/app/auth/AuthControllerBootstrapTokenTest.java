package com.melon.app.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerBootstrapTokenTest {

    @Test
    void bootstrapRequiresConfiguredToken() {
        AuthService authService = mock(AuthService.class);
        when(authService.hasUsers()).thenReturn(false);
        AuthController controller = new AuthController(authService, "expected-token");

        assertThrows(SecurityException.class, () -> controller.bootstrap(Map.of(
                "username", "admin",
                "password", "StrongPassword123",
                "bootstrap_token", "wrong-token"
        )).block());
    }

    @Test
    void validTokenCreatesTheInitialAdministrator() {
        AuthService authService = mock(AuthService.class);
        when(authService.hasUsers()).thenReturn(false);
        EnterprisePrincipal principal = new EnterprisePrincipal(
                "admin-id", "admin", "Administrator", true,
                Set.of("SYSTEM_ADMIN"), Set.of(), "", "");
        when(authService.login("admin", "StrongPassword123")).thenReturn(
                new AuthService.AuthResult(true, "token", Instant.now().plusSeconds(60), principal, null));
        AuthController controller = new AuthController(authService, "expected-token");

        var response = controller.bootstrap(Map.of(
                "username", "admin",
                "display_name", "Administrator",
                "password", "StrongPassword123",
                "bootstrap_token", "expected-token"
        )).block();

        assertEquals(200, response.getStatusCode().value());
        verify(authService).bootstrap("admin", "Administrator", "StrongPassword123");
    }

    @Test
    void statusOnlyDisclosesWhetherATokenIsRequired() {
        AuthService authService = mock(AuthService.class);
        when(authService.hasUsers()).thenReturn(false);
        AuthController controller = new AuthController(authService, "expected-token");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.status(null).block().getBody();
        assertEquals(true, body.get("bootstrap_token_required"));
    }
}
