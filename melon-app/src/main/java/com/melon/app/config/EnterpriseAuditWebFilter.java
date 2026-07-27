package com.melon.app.config;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.service.EnterpriseAuditService;
import com.melon.app.web.RequestIdSupport;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;

/** Records every authenticated enterprise-management write, including failures. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class EnterpriseAuditWebFilter implements WebFilter {

    private static final Set<HttpMethod> MUTATING = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final Set<String> MANAGEMENT_PREFIXES = Set.of(
            "/api/admin/", "/api/roles", "/api/project-spaces", "/api/projects",
            "/api/agents", "/api/models", "/api/providers", "/api/plugins",
            "/api/backups", "/api/quotas", "/api/config/security", "/api/envs", "/api/local-models");

    private final EnterpriseAuditService auditService;
    private final RuntimeExecutors executors;

    public EnterpriseAuditWebFilter(EnterpriseAuditService auditService, RuntimeExecutors executors) {
        this.auditService = auditService;
        this.executors = executors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (method == null || !MUTATING.contains(method) || !isManagementPath(path)) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .ofType(Authentication.class)
                .map(Authentication::getPrincipal)
                .ofType(EnterprisePrincipal.class)
                .flatMap(actor -> chain.filter(exchange)
                        .then(Mono.defer(() -> audit(exchange, actor, method, path, status(exchange))))
                        .onErrorResume(error -> audit(exchange, actor, method, path, "ERROR")
                                .then(Mono.error(error)))
                        .thenReturn(true))
                .switchIfEmpty(chain.filter(exchange).thenReturn(false))
                .then();
    }

    private Mono<Void> audit(ServerWebExchange exchange, EnterprisePrincipal actor,
                             HttpMethod method, String path, String result) {
        return Mono.fromRunnable(() -> auditService.record(
                        actor,
                        method.name(),
                        targetType(path),
                        path,
                        requestId(exchange),
                        sourceIp(exchange),
                        result,
                        Map.of(),
                        Map.of(),
                        Map.of("method", method.name(), "path", path)))
                .subscribeOn(executors.webBlockingScheduler())
                .then();
    }

    private boolean isManagementPath(String path) {
        return MANAGEMENT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String targetType(String path) {
        String[] segments = path.split("/");
        if (segments.length < 3) return "management";
        if ("config".equals(segments[2]) && segments.length > 3) return segments[3];
        if ("admin".equals(segments[2]) && segments.length > 3) return segments[3];
        return segments[2];
    }

    private String status(ServerWebExchange exchange) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int code = status != null ? status.value() : 200;
        return code >= 200 && code < 400 ? "SUCCESS" : "HTTP_" + code;
    }

    private String requestId(ServerWebExchange exchange) {
        return RequestIdSupport.resolve(exchange);
    }

    private String sourceIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "";
    }
}
