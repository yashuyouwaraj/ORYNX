package com.orynx.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "testSecretKeyForJwtSigning1234567890!@#$";
    private static final String OTHER_SECRET = "anotherTestSecretKeyForJwtSigning1234567!";

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(TEST_SECRET);
    }

    private String generateToken(String email, Long userId, long durationMs, String secret) {
        Key key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + durationMs));

        if (userId != null) {
            builder.claim("userId", userId);
        }

        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    @Test
    @DisplayName("Should reject anonymous request to protected endpoint with 401")
    void shouldRejectAnonymousRequestToProtectedEndpoint() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/workflows").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject invalid signature JWT with 401")
    void shouldRejectInvalidSignatureToken() {
        String token = generateToken("attacker@orynx.io", 999L, 3600000L, OTHER_SECRET);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/workflows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject expired JWT with 401")
    void shouldRejectExpiredToken() {
        String token = generateToken("user@orynx.io", 123L, -1000L, TEST_SECRET);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/workflows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject token missing userId claim with 401")
    void shouldRejectTokenMissingUserIdClaim() {
        String token = generateToken("user@orynx.io", null, 3600000L, TEST_SECRET);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/workflows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.empty();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should authenticate valid JWT and inject verified X-User-Id downstream")
    void shouldPassAndInjectVerifiedHeadersWithValidToken() {
        String token = generateToken("user42@orynx.io", 42L, 3600000L, TEST_SECRET);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/workflows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamExchange.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull(); // Not intercepted with error
        assertThat(downstreamExchange.get()).isNotNull();

        HttpHeaders downstreamHeaders = downstreamExchange.get().getRequest().getHeaders();
        assertThat(downstreamHeaders.getFirst("X-User-Id")).isEqualTo("42");
        assertThat(downstreamHeaders.getFirst("X-User-Email")).isEqualTo("user42@orynx.io");
    }

    @Test
    @DisplayName("Should strip client-supplied X-User-Id on public endpoints")
    void shouldStripClientSuppliedHeadersOnPublicEndpoint() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .header("X-User-Id", "999")
                .header("X-User-Email", "spoofed@orynx.io")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamExchange.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamExchange.get()).isNotNull();

        HttpHeaders downstreamHeaders = downstreamExchange.get().getRequest().getHeaders();
        assertThat(downstreamHeaders.getFirst("X-User-Id")).isNull();
        assertThat(downstreamHeaders.getFirst("X-User-Email")).isNull();
    }

    @Test
    @DisplayName("Should overwrite spoofed client X-User-Id with verified identity from JWT")
    void shouldOverwriteClientSuppliedHeadersOnProtectedEndpoint() {
        String token = generateToken("legit@orynx.io", 100L, 3600000L, TEST_SECRET);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/workflows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "999") // Client spoof attempt
                .header("X-User-Email", "attacker@orynx.io")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamExchange.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamExchange.get()).isNotNull();

        HttpHeaders downstreamHeaders = downstreamExchange.get().getRequest().getHeaders();
        assertThat(downstreamHeaders.getFirst("X-User-Id")).isEqualTo("100");
        assertThat(downstreamHeaders.getFirst("X-User-Email")).isEqualTo("legit@orynx.io");
    }
}
