package com.orynx.gateway.config;

import com.sun.net.httpserver.HttpExchange;
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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes Prometheus scrape metrics on a dedicated internal management port (default 9100)
 * bound strictly to the configured monitoring network interface or loopback address,
 * without altering external Actuator exposure on port 8080.
 */
@Component
@ConditionalOnProperty(name = "orynx.observability.metrics.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PrometheusMetricsServer {

    private final PrometheusMeterRegistry prometheusMeterRegistry;

    @Value("${orynx.observability.metrics.port:9100}")
    private int metricsPort;

    @Value("${orynx.observability.metrics.bind-address:127.0.0.1}")
    private String bindAddress = "127.0.0.1";

    @Value("${orynx.observability.metrics.network-prefix:}")
    private String networkPrefix = "";

    private HttpServer server;
    private ExecutorService executor;

    @PostConstruct
    public void start() throws IOException {
        InetAddress resolvedAddress = resolveBindAddress();
        AtomicInteger threadCounter = new AtomicInteger(1);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "prometheus-metrics-http-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        server = HttpServer.create(new InetSocketAddress(resolvedAddress, metricsPort), 0);
        server.setExecutor(executor);
        server.createContext("/metrics", this::handleMetricsRequest);
        server.start();
        log.info("Started internal Prometheus metrics endpoint on {}:{}",
                server.getAddress().getAddress().getHostAddress(),
                server.getAddress().getPort());
    }

    private void handleMetricsRequest(HttpExchange exchange) throws IOException {
        try {
            if (!isAllowedClient(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] payload = prometheusMeterRegistry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        } catch (Exception ex) {
            log.error("Failed to handle Prometheus metrics scrape request", ex);
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
                // Headers already sent
            }
        } finally {
            exchange.close();
        }
    }

    private boolean isAllowedClient(HttpExchange exchange) {
        if (networkPrefix == null || networkPrefix.isBlank()) {
            return true;
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return false;
        }
        InetAddress addr = remote.getAddress();
        return addr.isLoopbackAddress() || addr.getHostAddress().startsWith(networkPrefix.trim());
    }

    private InetAddress resolveBindAddress() throws IOException {
        if (networkPrefix != null && !networkPrefix.isBlank()) {
            String prefix = networkPrefix.trim();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    if (!iface.isUp() || iface.isLoopback()) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && addr.getHostAddress().startsWith(prefix)) {
                            return addr;
                        }
                    }
                }
            }
            log.warn("No active network interface matched prefix '{}'; falling back to bind-address '{}'",
                    prefix, bindAddress);
        }
        String target = (bindAddress == null || bindAddress.isBlank()) ? "127.0.0.1" : bindAddress.trim();
        return InetAddress.getByName(target);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }
}
