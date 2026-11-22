package com.orynx.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.List;

/**
 * Shared JWT authentication validation and unauthorized response helpers for Gateway filters.
 */
@Slf4j
final class GatewayJwtSupport {

    static final List<String> PUBLIC_ACTUATOR_PATHS = List.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    );

    private GatewayJwtSupport() {
    }

    static boolean isPublicActuatorPath(String path) {
        return path != null && PUBLIC_ACTUATOR_PATHS.contains(path);
    }

    /**
     * Extracts and validates the Bearer JWT from the request, invoking onValid on success or writing 401 on failure.
     */
    static ValidationOutcome validateRequest(ServerHttpRequest request, String secret) {
        String path = request.getPath().value();
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Unauthorized request to protected endpoint {}: Missing Bearer Authorization header", path);
            return ValidationOutcome.unauthorized("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return ValidationOutcome.unauthorized("Bearer token is empty");
        }

        if (secret == null || secret.isEmpty()) {
            log.error("JWT secret is not configured for request to {}", path);
            return ValidationOutcome.unauthorized("Invalid JWT token signature or format");
        }

        try {
            Key signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            if (claims.get("userId") == null) {
                log.warn("JWT token missing userId claim for request to {}", path);
                return ValidationOutcome.unauthorized("Token missing required userId claim");
            }

            return ValidationOutcome.valid(claims);
        } catch (ExpiredJwtException ex) {
            log.warn("Expired JWT token for request to {}: {}", path, ex.getMessage());
            return ValidationOutcome.unauthorized("JWT token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT token for request to {}: {}", path, ex.getMessage());
            return ValidationOutcome.unauthorized("Invalid JWT token signature or format");
        }
    }

    static Mono<Void> handleUnauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\"}", message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    record ValidationOutcome(Claims claims, String errorMessage) {
        static ValidationOutcome valid(Claims claims) {
            return new ValidationOutcome(claims, null);
        }

        static ValidationOutcome unauthorized(String errorMessage) {
            return new ValidationOutcome(null, errorMessage);
        }

        boolean isValid() {
            return claims != null;
        }
    }
}
