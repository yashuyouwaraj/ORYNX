package com.orynx.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.List;

@Component
@Slf4j
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/v1/auth",
            "/actuator"
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
        String path = request.getPath().value();

        // Security requirement: Remove/overwrite any client-supplied identity headers to prevent spoofing
        ServerHttpRequest.Builder mutatedRequestBuilder = request.mutate()
                .headers(httpHeaders -> {
                    httpHeaders.remove(HEADER_USER_ID);
                    httpHeaders.remove(HEADER_USER_EMAIL);
                });

        // Whitelist public endpoints
        if (isPublicPath(path)) {
            return chain.filter(exchange.mutate().request(mutatedRequestBuilder.build()).build());
        }

        // Protected endpoints require valid Bearer token
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Unauthorized request to protected endpoint {}: Missing Bearer Authorization header", path);
            return handleUnauthorized(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return handleUnauthorized(exchange, "Bearer token is empty");
        }

        try {
            Key signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Object userIdClaim = claims.get("userId");
            if (userIdClaim == null) {
                log.warn("JWT token missing userId claim for request to {}", path);
                return handleUnauthorized(exchange, "Token missing required userId claim");
            }

            String userId = userIdClaim.toString();
            String email = claims.getSubject();

            // Inject cryptographically verified identity headers downstream
            mutatedRequestBuilder.header(HEADER_USER_ID, userId);
            if (email != null && !email.isBlank()) {
                mutatedRequestBuilder.header(HEADER_USER_EMAIL, email);
            }

            return chain.filter(exchange.mutate().request(mutatedRequestBuilder.build()).build());

        } catch (ExpiredJwtException ex) {
            log.warn("Expired JWT token for request to {}: {}", path, ex.getMessage());
            return handleUnauthorized(exchange, "JWT token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT token for request to {}: {}", path, ex.getMessage());
            return handleUnauthorized(exchange, "Invalid JWT token signature or format");
        }
    }

    private boolean isPublicPath(String path) {
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> handleUnauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\"}", message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1; // Highest precedence before correlation and logging filters
    }
}
