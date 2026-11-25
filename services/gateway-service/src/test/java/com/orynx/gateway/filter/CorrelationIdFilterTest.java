package com.orynx.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesValidRequestAndCorrelationIdsWhenAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/workflows").build());
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        String reqId = captured.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        String corrId = captured.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER);
        assertThat(reqId).isNotBlank();
        assertThat(corrId).isEqualTo(reqId);
        assertThat(captured.get().getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(reqId);
        assertThat(captured.get().getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER)).isEqualTo(corrId);
    }

    @Test
    void rejectsMalformedCrlfHeadersAndReplacesWithSafeUuid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/workflows")
                        .header(CorrelationIdFilter.HEADER, "bad-id\r\nInjected: evil")
                        .header(CorrelationIdFilter.CORRELATION_HEADER, "x".repeat(200))
                        .build()
        );
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        String reqId = captured.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        String corrId = captured.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER);
        assertThat(reqId).doesNotContain("\r", "\n", "Injected");
        assertThat(corrId).isEqualTo(reqId);
    }
}
