package com.orynx.gateway.filter;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/v1/auth",
            "/api/v1/platform"
    );

    private static final List<String> EXACT_PUBLIC_PATHS = List.of(
            "/api/v1/users/register",
            "/api/v1/users/login",
            "/ws"
    );

    @Value("${jwt.secret:}")
    private String secret;

    public JwtAuthenticationFilter() {}

    public JwtAuthenticationFilter(String secret) {
        this.secret = secret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Security requirement: Remove/overwrite any client-supplied identity headers to prevent spoofing
        ServerHttpRequest.Builder mutatedRequestBuilder = request.mutate()
                .headers(httpHeaders -> {
                    httpHeaders.remove(HEADER_USER_ID);
                    httpHeaders.remove(HEADER_USER_EMAIL);
                });

        // Whitelist public endpoints
        if (isPublicRequest(request)) {
            return chain.filter(exchange.mutate().request(mutatedRequestBuilder.build()).build());
        }

        GatewayJwtSupport.ValidationOutcome outcome = GatewayJwtSupport.validateRequest(request, secret);
        if (!outcome.isValid()) {
            return GatewayJwtSupport.handleUnauthorized(exchange, outcome.errorMessage());
        }

        Claims claims = outcome.claims();
        String userId = claims.get("userId").toString();
        String email = claims.getSubject();

        // Inject cryptographically verified identity headers downstream
        mutatedRequestBuilder.header(HEADER_USER_ID, userId);
        if (email != null && !email.isBlank()) {
            mutatedRequestBuilder.header(HEADER_USER_EMAIL, email);
        }

        return chain.filter(exchange.mutate().request(mutatedRequestBuilder.build()).build());
    }

    private boolean isPublicRequest(ServerHttpRequest request) {
        String rawPath = GatewayJwtSupport.extractRawPath(request);
        if (rawPath == null) {
            return false;
        }
        if (GatewayJwtSupport.isActuatorPath(rawPath)) {
            return GatewayJwtSupport.isPublicActuatorHealthRequest(request);
        }
        if (EXACT_PUBLIC_PATHS.contains(rawPath)) {
            return true;
        }
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (rawPath.startsWith(prefix)) {
                return true;
            }
        }
        if (rawPath.startsWith("/ws/")) {
            return true;
        }
        return false;
    }

    @Override
    public int getOrder() {
        return -1; // Highest precedence before correlation and logging filters
    }
}
