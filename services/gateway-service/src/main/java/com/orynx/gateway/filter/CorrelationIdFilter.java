package com.orynx.gateway.filter;

import io.opentelemetry.api.trace.Span;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {
    public static final String HEADER = "X-Request-ID";
    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final int MAX_ID_LENGTH = 128;
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders reqHeaders = exchange.getRequest().getHeaders();
        String requestId = sanitizeOrGenerate(reqHeaders.getFirst(HEADER));
        String correlationId = sanitizeOrFallback(reqHeaders.getFirst(CORRELATION_HEADER), requestId);

        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            currentSpan.setAttribute("orynx.request.id", requestId);
            currentSpan.setAttribute("orynx.correlation.id", correlationId);
        }

        ServerWebExchange mutated = exchange.mutate()
                .request(exchange.getRequest()
                        .mutate()
                        .header(HEADER, requestId)
                        .header(CORRELATION_HEADER, correlationId)
                        .build())
                .build();

        mutated.getResponse().getHeaders().set(HEADER, requestId);
        mutated.getResponse().getHeaders().set(CORRELATION_HEADER, correlationId);

        return chain.filter(mutated);
    }

    public static String sanitizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_ID_LENGTH) {
            return null;
        }
        return SAFE_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    public static String sanitizeOrGenerate(String raw) {
        String safe = sanitizeOrNull(raw);
        return safe != null ? safe : UUID.randomUUID().toString();
    }

    public static String sanitizeOrFallback(String raw, String fallback) {
        String safe = sanitizeOrNull(raw);
        return safe != null ? safe : fallback;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
