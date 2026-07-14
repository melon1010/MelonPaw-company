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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private EnterprisePrincipal principal(Set<String> permissions) {
        return new EnterprisePrincipal(
                "user-1", "employee", "Employee", true,
                Set.of("EMPLOYEE"), permissions, "", "");
    }
}
