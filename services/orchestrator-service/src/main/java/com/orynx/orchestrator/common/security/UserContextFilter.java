package com.orynx.orchestrator.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class UserContextFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/v1/platform",
            "/ws"
    );

    private static final Set<String> EXACT_PUBLIC_ACTUATOR_HEALTH_PATHS = Set.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    );

    private final OrchestratorJwtService orchestratorJwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isPublicPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        String headerUserId = request.getHeader(HEADER_USER_ID);

        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();

                Claims claims = orchestratorJwtService.parseAndValidateToken(token);
                Object userIdClaim = claims.get("userId");

                if (userIdClaim == null) {
                    log.warn("Direct/Orchestrator request to {} has token without userId claim", path);
                    writeUnauthorized(response, "Token missing required userId claim");
                    return;
                }

                Long tokenUserId;
                if (userIdClaim instanceof Number number) {
                    tokenUserId = number.longValue();
                } else {
                    try {
                        tokenUserId = Long.parseLong(userIdClaim.toString());
                    } catch (NumberFormatException e) {
                        writeUnauthorized(response, "Token contains malformed userId claim");
                        return;
                    }
                }

                // Header spoofing check: If client or gateway passed X-User-Id, it MUST match token claim
                if (headerUserId != null && !headerUserId.isBlank()) {
                    try {
                        long parsedHeaderId = Long.parseLong(headerUserId.trim());
                        if (parsedHeaderId != tokenUserId) {
                            log.error("Spoofing attempt detected on path {}: header X-User-Id={} does not match JWT userId={}",
                                    path, parsedHeaderId, tokenUserId);
                            writeUnauthorized(response, "Header spoofing detected: X-User-Id does not match token claim");
                            return;
                        }
                    } catch (NumberFormatException e) {
                        writeUnauthorized(response, "Malformed X-User-Id header");
                        return;
                    }
                }

                String email = claims.getSubject();
                UserContext.set(tokenUserId, email);

                if (isActuatorRequest(path)) {
                    log.warn("Blocked disallowed Actuator endpoint access on orchestrator-service: {} {}",
                            request.getMethod(), path);
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"status\":404,\"error\":\"Not Found\"}");
                    return;
                }

            } else {
                // No valid Bearer token provided
                if (headerUserId != null && !headerUserId.isBlank()) {
                    log.error("Direct unauthenticated caller attempted to pass X-User-Id={} to {}", headerUserId, path);
                    writeUnauthorized(response, "Authentication required: direct X-User-Id spoofing prohibited");
                    return;
                }

                writeUnauthorized(response, "Authentication required: missing Bearer token");
                return;
            }

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException ex) {
            log.warn("Expired JWT token on {}: {}", path, ex.getMessage());
            writeUnauthorized(response, "JWT token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT token on {}: {}", path, ex.getMessage());
            writeUnauthorized(response, "Invalid JWT token signature or format");
        } finally {
            UserContext.clear();
        }
    }

    private boolean isPublicPath(HttpServletRequest request) {
        String rawPath = request.getRequestURI();
        if (rawPath == null) {
            return false;
        }
        if (isActuatorRequest(rawPath)) {
            return isAllowedPublicHealthRequest(request.getMethod(), rawPath);
        }
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (rawPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isActuatorRequest(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String normalized = normalizePath(rawPath);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("/actuator") || lower.startsWith("/actuator/") || lower.startsWith("/actuator;")) {
            return true;
        }
        String rawLower = rawPath.toLowerCase(Locale.ROOT);
        return rawLower.contains("/actuator") && (rawPath.contains(";") || rawPath.contains(".."));
    }

    static boolean isAllowedPublicHealthRequest(String method, String rawPath) {
        if (rawPath == null || method == null) {
            return false;
        }
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        if (rawPath.contains(";") || rawPath.contains("..")) {
            return false;
        }
        return EXACT_PUBLIC_ACTUATOR_HEALTH_PATHS.contains(normalizePath(rawPath));
    }

    static String normalizePath(String rawPath) {
        String normalized = rawPath.replaceAll("/+", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String body = String.format("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\"}", message);
        response.getWriter().write(body);
    }
}
