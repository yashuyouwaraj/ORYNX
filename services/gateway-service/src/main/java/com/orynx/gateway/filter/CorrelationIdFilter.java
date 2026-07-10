package com.orynx.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {
    public static final String HEADER = "X-Request-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER);

        if(requestId ==null){
            requestId = UUID.randomUUID().toString();
        }

        exchange = exchange.mutate()
                        .request(exchange.getRequest()
                                .mutate()
                                .header(HEADER,requestId)
                                .build()
                        )
                                .build();

        exchange.getResponse()
                .getHeaders()
                .add(HEADER, requestId);

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
