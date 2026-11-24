package com.orynx.orchestrator.common.config;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Exposes Prometheus scrape metrics on a dedicated internal management port (default 9100)
 * without altering Actuator HTTP boundary controls on port 8082.
 */
@Component
@ConditionalOnProperty(name = "orynx.observability.metrics.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PrometheusMetricsServer {

    private final PrometheusMeterRegistry prometheusMeterRegistry;

    @Value("${orynx.observability.metrics.port:9100}")
    private int metricsPort;

    private HttpServer server;

    @PostConstruct
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(metricsPort), 0);
        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            byte[] payload = prometheusMeterRegistry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        log.info("Started internal Prometheus metrics endpoint on port {}", server.getAddress().getPort());
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }
}
