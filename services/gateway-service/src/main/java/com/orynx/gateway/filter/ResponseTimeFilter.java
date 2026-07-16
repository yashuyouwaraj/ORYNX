package com.orynx.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class ResponseTimeFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();

        return chain.filter(exchange)
                .then(
                        Mono.fromRunnable(()->{
                            long duration = System.currentTimeMillis() - start;

                            log.info(
                                    "{} {} completed in {} ms",
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getPath(),
                                    duration
                            );
                        })
                )
                ;
    }

    @Override
    public int getOrder() {
        return 2;
    }
}
