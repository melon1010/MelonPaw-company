package com.melon.app.config;

import com.melon.app.auth.AuthService;
import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;

import static com.melon.app.auth.EnterprisePermissions.AGENTS_MANAGE;
import static com.melon.app.auth.EnterprisePermissions.AGENTS_USE;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_AUTOMATIONS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_CHANNELS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_SKILLS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_WORKSPACE;

/** Bearer-token security for the private enterprise deployment. */
@Configuration
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            AuthService authService,
            EnterpriseAgentAccessService agentAccessService) {
        AuthenticationWebFilter bearerFilter = bearerFilter(authService);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, error) -> writeError(
                                exchange, HttpStatus.UNAUTHORIZED, "Authentication is required"))
                        .accessDeniedHandler((exchange, error) -> writeError(
                                exchange, HttpStatus.FORBIDDEN, "Permission denied")))
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST,
                                "/api/auth/login", "/api/auth/bootstrap", "/api/auth/register").permitAll()
                        .pathMatchers(HttpMethod.GET,
                                "/api/auth/status", "/api/agent/health").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/channels/onebot/websocket").permitAll()
                        .pathMatchers("/", "/console", "/console/**", "/assets/**", "/favicon.ico",
                                "/*.js", "/*.css", "/*.map", "/*.png", "/*.svg", "/*.woff", "/*.woff2").permitAll()

                        // Identity and project-space controllers apply their finer-grained
                        // permissions with @PreAuthorize and membership checks.
                        .pathMatchers("/api/auth/**", "/api/admin/users/**", "/api/roles/**",
                                "/api/project-spaces/**", "/api/projects/**").authenticated()
                        .pathMatchers("/api/version", "/api/settings/**").authenticated()

                        // Employees can use published agents and can only access chats after the
                        // controller has bound the request to the authenticated principal.
                        .pathMatchers(HttpMethod.GET, "/api/agents", "/api/agents/*")
                                .hasAuthority("agents:use")
                        .pathMatchers("/api/chats/**", "/api/console/chat", "/api/console/chat/**",
                                "/api/console/upload", "/api/console/push-messages",
                                "/api/console/inbox/**")
                                .hasAuthority("agents:use")
                        .pathMatchers("/api/commands/check")
                                .hasAuthority("agents:use")

                        // Approval actions are available to designated approvers and to the user
                        // handling an approval generated by their own interactive task.
                        .pathMatchers("/api/approval/**")
                                .hasAnyAuthority("approvals:decide", "agents:use")

                        // Usage and audit data are operational records, not general employee data.
                        .pathMatchers("/api/token-usage/**", "/api/config/security/audit-events")
                                .hasAnyAuthority("audit:read", "agents:manage", "system:manage")

                        // Employees may inspect the published model catalog and select an
                        // effective model for their own runtime. Provider credentials and global
                        // model administration stay system-scoped.
                        .pathMatchers(HttpMethod.GET, "/api/models", "/api/models/active")
                                .hasAuthority("agents:use")
                        .pathMatchers(HttpMethod.PUT, "/api/models/active")
                                .hasAuthority("agents:use")

                        // MCP server registration, OAuth material and direct server tool calls
                        // are deployment-level capabilities, not Agent configuration.
                        .pathMatchers("/api/mcp/**")
                                .hasAuthority("system:manage")

                        // Agent operators manage the runtime surface. Channel webhooks remain
                        // protected here until a channel-specific signature has been verified;
                        // internal polling and WebSocket adapters are unaffected.
                        .pathMatchers(HttpMethod.PUT, "/api/agents/order")
                                .hasAuthority("system:manage")
                        .pathMatchers(HttpMethod.POST, "/api/agents", "/api/agents/**")
                                .hasAuthority("agents:manage")
                        .pathMatchers(HttpMethod.PUT, "/api/agents/**")
                                .hasAuthority("agents:manage")
                        .pathMatchers(HttpMethod.PATCH, "/api/agents/**")
                                .hasAuthority("agents:manage")
                        .pathMatchers(HttpMethod.DELETE, "/api/agents/**")
                                .hasAuthority("agents:manage")
                        .pathMatchers(HttpMethod.GET, "/api/skills/pool")
                                .hasAnyAuthority(PERSONAL_SKILLS, AGENTS_MANAGE)
                        .pathMatchers(HttpMethod.POST, "/api/skills/pool/refresh", "/api/skills/pool/download")
                                .hasAnyAuthority(PERSONAL_SKILLS, AGENTS_MANAGE)
                        .pathMatchers("/api/skills/pool/**", "/api/skills/hub/**", "/api/skills/workspaces",
                                "/api/workspace/running-config", "/api/workspace/running-config/**",
                                "/api/agent-stats/**")
                                .hasAuthority("agents:manage")

                        // Personal capabilities are independently assignable. Agent operators use
                        // the same APIs for managed non-personal Agents.
                        .pathMatchers("/api/skills/**")
                                .hasAnyAuthority(PERSONAL_SKILLS, AGENTS_MANAGE)
                        .pathMatchers("/api/tools/**")
                                .hasAuthority("system:manage")
                        .pathMatchers("/api/config/channels/**", "/api/channels/**",
                                "/api/access-control/**")
                                .hasAnyAuthority(PERSONAL_CHANNELS, AGENTS_MANAGE)
                        .pathMatchers("/api/cron/**", "/api/config/heartbeat", "/api/config/heartbeat/**")
                                .hasAnyAuthority(PERSONAL_AUTOMATIONS, AGENTS_MANAGE)
                        .pathMatchers("/api/files/preview/**", "/api/workspace/**")
                                .hasAnyAuthority(PERSONAL_WORKSPACE, AGENTS_MANAGE)

                        // Secrets, executable extensions, backups and host-level controls are
                        // intentionally reserved for system administrators.
                        .pathMatchers("/api/envs/**", "/api/backups/**", "/api/plugins/**", "/api/models/**",
                                "/api/providers/**", "/api/local-models/**", "/api/config/security/**",
                                "/api/debug/**", "/api/console/debug/**", "/api/agent/shutdown",
                                "/api/agent/admin/**")
                                .hasAuthority("system:manage")

                        // Compatibility endpoints not listed above still require an account that
                        // is allowed to use an Agent. Read-only auditors cannot forge Agent scope.
                        .pathMatchers("/api/**").hasAuthority("agents:use")
                        .anyExchange().authenticated()
                )
                .addFilterAt(bearerFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(agentScopeFilter(agentAccessService), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    WebFilter agentScopeFilter(EnterpriseAgentAccessService agentAccessService) {
        return (exchange, chain) -> ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication().getPrincipal())
                .ofType(EnterprisePrincipal.class)
                .map(principal -> {
                    if (!hasAgentScopedCapability(principal)) {
                        return new AgentScopeDecision(exchange, null);
                    }
                    try {
                        String requested = exchange.getRequest().getHeaders().getFirst("X-Agent-Id");
                        String path = exchange.getRequest().getPath().pathWithinApplication().value();
                        String effective = isPersonalExecutionPath(path)
                                ? agentAccessService.ensurePersonalAgent(principal)
                                : agentAccessService.resolve(principal, requested);
                        var request = exchange.getRequest().mutate()
                                .headers(headers -> headers.set("X-Agent-Id", effective))
                                .build();
                        return new AgentScopeDecision(exchange.mutate().request(request).build(), null);
                    } catch (SecurityException e) {
                        return new AgentScopeDecision(exchange, e.getMessage());
                    }
                })
                .defaultIfEmpty(new AgentScopeDecision(exchange, null))
                .flatMap(decision -> decision.deniedReason() == null
                        ? chain.filter(decision.exchange())
                        : writeError(exchange, HttpStatus.FORBIDDEN, decision.deniedReason()));
    }

    boolean isPersonalExecutionPath(String path) {
        return path.startsWith("/api/chats")
                || path.startsWith("/api/console/chat")
                || path.startsWith("/api/console/upload")
                || path.startsWith("/api/console/push-messages")
                || path.startsWith("/api/console/inbox")
                || path.startsWith("/api/commands/check")
                || path.startsWith("/api/models/active")
                || path.startsWith("/api/skills")
                || path.startsWith("/api/tools")
                || path.startsWith("/api/workspace")
                || path.startsWith("/api/config/channels")
                || path.startsWith("/api/channels")
                || path.startsWith("/api/access-control")
                || path.startsWith("/api/cron")
                || path.startsWith("/api/config/heartbeat")
                || path.startsWith("/api/files")
                || path.startsWith("/api/agent-stats");
    }

    private boolean hasAgentScopedCapability(EnterprisePrincipal principal) {
        return principal.hasAnyPermission(
                AGENTS_USE,
                AGENTS_MANAGE,
                PERSONAL_WORKSPACE,
                PERSONAL_SKILLS,
                PERSONAL_CHANNELS,
                PERSONAL_AUTOMATIONS
        );
    }

    private record AgentScopeDecision(ServerWebExchange exchange, String deniedReason) {
    }

    private AuthenticationWebFilter bearerFilter(AuthService authService) {
        ReactiveAuthenticationManager authenticationManager = authentication ->
                Mono.fromCallable(() -> authService.authenticateToken(String.valueOf(authentication.getCredentials())))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(Mono::justOrEmpty)
                        .map(principal -> (Authentication) UsernamePasswordAuthenticationToken.authenticated(
                                principal, authentication.getCredentials(), principal.authorities()))
                        .switchIfEmpty(Mono.error(new BadCredentialsException("Invalid or expired access token")));
        AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager);
        filter.setServerAuthenticationConverter(exchange -> {
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            if ("/api/channels/onebot/websocket".equals(path)) {
                // This upgrade uses the per-Agent OneBot token, which is verified by
                // OneBotWebSocketHandler instead of the application login token.
                return Mono.empty();
            }
            String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Mono.empty();
            }
            String token = authorization.substring(7).trim();
            if (token.isEmpty()) {
                return Mono.error(new BadCredentialsException("Bearer token is empty"));
            }
            return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated("bearer", token));
        });
        filter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        filter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(
                (exchange, error) -> writeError(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired access token")));
        return filter;
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String detail) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"status\":\"error\",\"detail\":\"" + detail + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
