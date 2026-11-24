package com.orynx.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_MDC_KEY = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path != null && path.startsWith("/actuator/health")) {
            return chain.filter(exchange);
        }

        String requestId = exchange.getRequest()
                .getHeaders()
                .getFirst("X-Request-ID");

        if (requestId != null && !requestId.isBlank()) {
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
        }
        try {
            log.info(
                    "[{}] Incoming Request -> {} {}",
                    requestId != null ? requestId : "-",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI()
            );
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
