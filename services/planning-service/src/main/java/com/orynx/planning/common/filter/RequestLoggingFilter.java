package com.orynx.planning.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_MDC_KEY = "requestId";

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

        try {
            String uri = request.getRequestURI();
            if (uri == null || !uri.startsWith("/actuator/health")) {
                log.info(
                        "[{}] {} {}",
                        requestId != null ? requestId : "-",
                        request.getMethod(),
                        uri
                );
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }
}
