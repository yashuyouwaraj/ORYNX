package com.orynx.orchestrator.common.filter;

import com.orynx.orchestrator.common.observability.ObservabilitySanitizer;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String WORKFLOW_ID_MDC_KEY = "workflowId";

    private static final Pattern WORKFLOW_URI_PATTERN =
            Pattern.compile("^/api/v1/(?:workflows|timeline)/(\\d+)(?:/.*)?$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = ObservabilitySanitizer.sanitizeCorrelationId(request.getHeader("X-Request-ID"));
        String correlationId = ObservabilitySanitizer.sanitizeCorrelationId(request.getHeader("X-Correlation-ID"));
        if (correlationId == null) {
            correlationId = requestId;
        }

        if (requestId != null) {
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
        }
        if (correlationId != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }

        Span currentSpan = Span.current();
        boolean validSpan = currentSpan != null && currentSpan.getSpanContext().isValid();
        if (validSpan && correlationId != null) {
            currentSpan.setAttribute("orynx.correlation.id", correlationId);
        }

        String uri = request.getRequestURI();
        if (uri != null) {
            Matcher matcher = WORKFLOW_URI_PATTERN.matcher(uri);
            if (matcher.matches()) {
                String workflowId = matcher.group(1);
                MDC.put(WORKFLOW_ID_MDC_KEY, workflowId);
                if (validSpan) {
                    currentSpan.setAttribute("orynx.workflow.id", workflowId);
                }
            }
        }

        try {
            if (uri == null || !uri.startsWith("/actuator/health")) {
                log.info(
                        "[{}] {} {} from {}",
                        correlationId != null ? correlationId : "-",
                        request.getMethod(),
                        uri,
                        request.getRemoteAddr()
                );
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
            MDC.remove(CORRELATION_ID_MDC_KEY);
            MDC.remove(WORKFLOW_ID_MDC_KEY);
            MDC.remove("executionId");
        }
    }
}
