package com.orynx.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Enforces an explicit HTTP boundary for local Gateway WebFlux Actuator management endpoints,
 * requiring valid JWT authentication on non-health paths and returning 404 even if deployment
 * environment variables attempt to re-enable sensitive endpoints.
 */
@Component
public class ActuatorSecurityWebFilter implements WebFilter, Ordered {

    @Value("${jwt.secret:}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = GatewayJwtSupport.extractRawPath(request);

        if (!GatewayJwtSupport.isActuatorPath(rawPath) || GatewayJwtSupport.isPublicActuatorHealthRequest(request)) {
            return chain.filter(exchange);
        }

        GatewayJwtSupport.ValidationOutcome outcome = GatewayJwtSupport.validateRequest(request, secret);
        if (!outcome.isValid()) {
            return GatewayJwtSupport.handleUnauthorized(exchange, outcome.errorMessage());
        }

        // Defense-in-depth: block all non-health Actuator paths and method/path variations even for authenticated callers
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
