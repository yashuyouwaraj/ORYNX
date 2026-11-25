package com.orynx.planning.common.filter;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
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
            String uri = request.getRequestURI();
            if (uri == null || !uri.startsWith("/actuator/health")) {
                log.info(
                        "[{}] {} {}",
                        correlationId != null ? correlationId : "-",
                        request.getMethod(),
                        uri
                );
            }

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
