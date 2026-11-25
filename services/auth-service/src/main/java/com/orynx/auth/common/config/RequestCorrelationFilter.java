package com.orynx.auth.common.config;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader("X-Request-ID"));
        String correlationId = sanitize(request.getHeader("X-Correlation-ID"));
        if (correlationId == null) {
            correlationId = requestId;
        }

        if (requestId != null) {
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
        }
        if (correlationId != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            Span currentSpan = Span.current();
            if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
                currentSpan.setAttribute("orynx.correlation.id", correlationId);
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return SAFE_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }
}
