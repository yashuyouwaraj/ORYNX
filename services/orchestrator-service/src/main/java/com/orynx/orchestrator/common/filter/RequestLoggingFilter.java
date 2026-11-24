package com.orynx.orchestrator.common.filter;

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
    public static final String WORKFLOW_ID_MDC_KEY = "workflowId";

    private static final Pattern WORKFLOW_URI_PATTERN =
            Pattern.compile("^/api/v1/(?:workflows|timeline)/(\\d+)(?:/.*)?$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
        }

        String uri = request.getRequestURI();
        if (uri != null) {
            Matcher matcher = WORKFLOW_URI_PATTERN.matcher(uri);
            if (matcher.matches()) {
                MDC.put(WORKFLOW_ID_MDC_KEY, matcher.group(1));
            }
        }

        try {
            if (uri == null || !uri.startsWith("/actuator/health")) {
                log.info(
                        "[{}] {} {} from {}",
                        requestId != null ? requestId : "-",
                        request.getMethod(),
                        uri,
                        request.getRemoteAddr()
                );
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
            MDC.remove(WORKFLOW_ID_MDC_KEY);
        }
    }
}
