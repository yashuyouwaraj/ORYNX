package com.orynx.orchestrator.common.config;

import com.orynx.orchestrator.common.filter.RequestLoggingFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    @Bean
    public WebClient.Builder webClientBuilder(){
        return WebClient.builder()
                .filter((request, next) -> {
                    String requestId = MDC.get(RequestLoggingFilter.REQUEST_ID_MDC_KEY);
                    if (requestId == null || requestId.isBlank()
                            || request.headers().containsKey("X-Request-ID")) {
                        return next.exchange(request);
                    }

                    return next.exchange(
                            org.springframework.web.reactive.function.client.ClientRequest.from(request)
                                    .header("X-Request-ID", requestId)
                                    .build()
                    );
                });
    }
}
