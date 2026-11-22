package com.orynx.auth.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Post-authorization boundary filter blocking non-health Actuator requests and path/method
 * normalization bypasses with 404 Not Found even for authenticated callers or override configs.
 * Registered explicitly after Spring Security's AuthorizationFilter so unauthenticated requests
 * continue to be rejected with 401/403 by Spring Security.
 */
@Slf4j
public class ActuatorBoundaryFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_HEALTH_PATHS = Set.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String rawPath = request.getRequestURI();
        if (isActuatorRequest(rawPath) && !isAllowedPublicHealthRequest(request.getMethod(), rawPath)) {
            log.warn("Blocked disallowed Actuator endpoint access on auth-service: {} {}", request.getMethod(), rawPath);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":404,\"error\":\"Not Found\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
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
        return ALLOWED_HEALTH_PATHS.contains(normalizePath(rawPath));
    }

    static String normalizePath(String rawPath) {
        String normalized = rawPath.replaceAll("/+", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
