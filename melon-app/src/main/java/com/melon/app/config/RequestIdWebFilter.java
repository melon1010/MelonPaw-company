package com.melon.app.config;

import com.melon.app.web.RequestIdSupport;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Correlates every API response and error without trusting arbitrary header content. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = RequestIdSupport.resolve(exchange);
        exchange.getAttributes().put(RequestIdSupport.ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(RequestIdSupport.HEADER, requestId);
        return chain.filter(exchange);
    }
}
