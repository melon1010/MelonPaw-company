package com.melon.app.config;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.melon.app.auth.EnterprisePermissions.AGENTS_MANAGE;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_AUTOMATIONS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_CHANNELS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_SKILLS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_WORKSPACE;

class SecurityConfigTest {

    @Test
    void authenticatedRequestRunsFilterChainExactlyOnce() {
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal principal = principal(Set.of("agents:use"));
        when(accessService.resolve(principal, "requested-agent")).thenReturn("resolved-agent");

        WebFilter filter = new SecurityConfig().agentScopeFilter(accessService);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chats")
                        .header("X-Agent-Id", "requested-agent")
                        .build());
        AtomicInteger invocations = new AtomicInteger();

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, "token", principal.authorities());
        filter.filter(exchange, filteredExchange -> {
                invocations.incrementAndGet();
                assertEquals("resolved-agent",
                        filteredExchange.getRequest().getHeaders().getFirst("X-Agent-Id"));
                return Mono.empty();
            })
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
            .block();

        assertEquals(1, invocations.get());
        verify(accessService).resolve(principal, "requested-agent");
    }

    @Test
    void unauthenticatedRequestRunsFilterChainExactlyOnce() {
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        WebFilter filter = new SecurityConfig().agentScopeFilter(accessService);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/agent/health").build());
        AtomicInteger invocations = new AtomicInteger();

        filter.filter(exchange, filteredExchange -> {
                invocations.incrementAndGet();
                return Mono.empty();
            })
            .block();

        assertEquals(1, invocations.get());
    }

    @Test
    void personalCapabilitiesForceTheAuthenticatedUsersPersonalAgent() {
        Object[][] requests = {
                {"/api/workspace", PERSONAL_WORKSPACE},
                {"/api/workspace/upload", PERSONAL_WORKSPACE},
                {"/api/workspace/download", PERSONAL_WORKSPACE},
                {"/api/workspace/audio-mode", PERSONAL_WORKSPACE},
                {"/api/skills", PERSONAL_SKILLS},
                {"/api/tools", PERSONAL_WORKSPACE},
                {"/api/config/channels/feishu", PERSONAL_CHANNELS},
                {"/api/access-control/feishu", PERSONAL_CHANNELS},
                {"/api/cron/jobs", PERSONAL_AUTOMATIONS},
                {"/api/config/heartbeat/run", PERSONAL_AUTOMATIONS}
        };

        for (Object[] request : requests) {
            EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
            EnterprisePrincipal principal = principal(Set.of(String.valueOf(request[1])));
            when(accessService.ensurePersonalAgent(principal)).thenReturn("personal-user-1");
            WebFilter filter = new SecurityConfig().agentScopeFilter(accessService);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(String.valueOf(request[0]))
                            .header("X-Agent-Id", "forged-agent")
                            .build());
            AtomicInteger invocations = new AtomicInteger();

            filter.filter(exchange, filteredExchange -> {
                        invocations.incrementAndGet();
                        assertEquals("personal-user-1",
                                filteredExchange.getRequest().getHeaders().getFirst("X-Agent-Id"));
                        return Mono.empty();
                    })
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(principal)))
                    .block();

            assertEquals(1, invocations.get(), String.valueOf(request[0]));
            verify(accessService).ensurePersonalAgent(principal);
            verify(accessService, never()).resolve(principal, "forged-agent");
        }
    }

    @Test
    void agentOperatorStillPassesThroughResourceOwnershipResolution() {
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal principal = principal(Set.of(AGENTS_MANAGE));
        when(accessService.resolve(principal, "managed-agent")).thenReturn("managed-agent");
        WebFilter filter = new SecurityConfig().agentScopeFilter(accessService);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/workspace")
                        .header("X-Agent-Id", "managed-agent")
                        .build());
        AtomicInteger invocations = new AtomicInteger();

        filter.filter(exchange, filteredExchange -> {
                    invocations.incrementAndGet();
                    assertEquals("managed-agent",
                            filteredExchange.getRequest().getHeaders().getFirst("X-Agent-Id"));
                    return Mono.empty();
                })
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(principal)))
                .block();

        assertEquals(1, invocations.get());
        verify(accessService).resolve(principal, "managed-agent");
    }

    @Test
    void principalWithoutAgentCapabilitiesDoesNotResolveAnAgentScope() {
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal principal = principal(Set.of("projects:manage"));
        WebFilter filter = new SecurityConfig().agentScopeFilter(accessService);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/projects").build());
        AtomicInteger invocations = new AtomicInteger();

        filter.filter(exchange, filteredExchange -> {
                    invocations.incrementAndGet();
                    assertNull(filteredExchange.getRequest().getHeaders().getFirst("X-Agent-Id"));
                    return Mono.empty();
                })
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(principal)))
                .block();

        assertEquals(1, invocations.get());
        verifyNoInteractions(accessService);
    }

    private Authentication authentication(EnterprisePrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, "token", principal.authorities());
    }

    private EnterprisePrincipal principal(Set<String> permissions) {
        return new EnterprisePrincipal(
                "user-1", "employee", "Employee", true,
                Set.of("EMPLOYEE"), permissions, "", "");
    }
}
