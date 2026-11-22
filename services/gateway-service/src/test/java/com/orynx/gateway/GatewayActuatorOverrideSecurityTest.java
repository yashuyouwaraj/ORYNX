package com.orynx.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=testSecretKeyForJwtSigning1234567890!@#$",
                "management.endpoints.web.exposure.include=*,health,env,beans,mappings",
                "management.endpoints.web.exposure.exclude=",
                "management.endpoints.web.discovery.enabled=true",
                "management.endpoint.health.show-details=always",
                "management.endpoint.health.show-components=always",
                "management.endpoint.health.group.readiness.show-details=always",
                "management.endpoint.health.group.readiness.show-components=always",
                "management.endpoint.gateway.access=unrestricted"
        }
)
@AutoConfigureWebTestClient
class GatewayActuatorOverrideSecurityTest {

    private static final String TEST_SECRET = "testSecretKeyForJwtSigning1234567890!@#$";

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WebEndpointProperties webEndpointProperties;

    @Autowired
    private HealthEndpointProperties healthEndpointProperties;

    private String generateValidToken() {
        Key key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .setSubject("admin@orynx.io")
                .claim("userId", 1L)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    @DisplayName("Gateway Actuator policy resists hostile environment overrides")
    void hostileOverridesAreNeutralizedAtRuntime() {
        assertThat(webEndpointProperties.getExposure().getInclude())
                .containsExactly("health")
                .doesNotContain("*", "env", "beans", "mappings");
        assertThat(webEndpointProperties.getDiscovery().isEnabled()).isFalse();
        assertThat(healthEndpointProperties.getShowDetails()).isEqualTo(Show.NEVER);
        assertThat(healthEndpointProperties.getShowComponents()).isEqualTo(Show.NEVER);
        assertThat(healthEndpointProperties.getGroup().get("readiness").getShowDetails()).isEqualTo(Show.NEVER);
        assertThat(healthEndpointProperties.getGroup().get("readiness").getShowComponents()).isEqualTo(Show.NEVER);
    }

    @Test
    @DisplayName("Gateway health probe omits details even under hostile overrides")
    void healthProbeRemainsCleanUnderHostileOverrides() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.details").doesNotExist()
                .jsonPath("$.components").doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/env",
            "/actuator/env/",
            "//actuator/env",
            "/ACTUATOR/env",
            "/actuator;param=1/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/heapdump",
            "/actuator/gateway/routes"
    })
    @DisplayName("Sensitive Actuator paths return 404 to authenticated callers even with hostile overrides")
    void sensitiveEndpointsBlockedEvenWhenAuthenticatedUnderOverrides(String path) {
        webTestClient.get()
                .uri(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + generateValidToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /actuator/health is rejected when anonymous (401) and blocked when authenticated (404)")
    void nonGetHealthProbeRejectedUnderHostileOverrides() {
        webTestClient.post()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post()
                .uri("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + generateValidToken())
                .exchange()
                .expectStatus().isNotFound();
    }
}
