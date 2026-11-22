package com.orynx.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Protects local Gateway WebFlux Actuator management endpoints, because Spring Cloud Gateway
 * GlobalFilters only execute on proxied routes and not on local management endpoints.
 */
@Component
public class ActuatorSecurityWebFilter implements WebFilter, Ordered {

    @Value("${jwt.secret:}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (!isActuatorPath(path) || GatewayJwtSupport.isPublicActuatorPath(path)) {
            return chain.filter(exchange);
        }

        GatewayJwtSupport.ValidationOutcome outcome = GatewayJwtSupport.validateRequest(request, secret);
        if (!outcome.isValid()) {
            return GatewayJwtSupport.handleUnauthorized(exchange, outcome.errorMessage());
        }

        return chain.filter(exchange);
    }

    private boolean isActuatorPath(String path) {
        return path != null && (path.equals("/actuator") || path.startsWith("/actuator/"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
