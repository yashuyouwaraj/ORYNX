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
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=testSecretKeyForJwtSigning1234567890!@#$"
        }
)
@AutoConfigureWebTestClient
class ActuatorSecurityIntegrationTest {

    private static final String TEST_SECRET = "testSecretKeyForJwtSigning1234567890!@#$";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WebEndpointProperties webEndpointProperties;

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
    @DisplayName("Gateway WebEndpointProperties must expose only health without wildcard")
    void gatewayMustExposeOnlyHealthWithoutWildcard() {
        assertThat(webEndpointProperties.getExposure().getInclude())
                .containsExactly("health")
                .doesNotContain("*");
        assertThat(webEndpointProperties.getExposure().getExclude())
                .contains("env", "configprops", "beans", "mappings", "heapdump",
                          "threaddump", "loggers", "shutdown", "conditions", "scheduledtasks", "info", "gateway");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    })
    @DisplayName("Public health and probe endpoints return 200 UP without exposing details or components")
    void publicHealthEndpointsReturnStatusWithoutDetails(String path) {
        webTestClient.get()
                .uri(path)
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
            "/ACTUATOR/env",
            "/actuator;param=1/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/loggers",
            "/actuator/shutdown",
            "/actuator/conditions",
            "/actuator/scheduledtasks",
            "/actuator/info",
            "/actuator/gateway/routes",
            "/actuator/health/custom"
    })
    @DisplayName("Anonymous requests to non-health Gateway Actuator paths are rejected with 401")
    void anonymousRequestsToSensitiveActuatorPathsAreRejected(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Anonymous non-GET requests to health endpoint are rejected with 401")
    void anonymousPostToHealthEndpointIsRejected() {
        webTestClient.post()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/env",
            "/actuator/env/",
            "/ACTUATOR/env",
            "/actuator;param=1/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/loggers",
            "/actuator/shutdown",
            "/actuator/conditions",
            "/actuator/scheduledtasks",
            "/actuator/info",
            "/actuator/gateway/routes"
    })
    @DisplayName("Authenticated requests confirm sensitive Actuator endpoints are disabled (404)")
    void sensitiveActuatorEndpointsAreNotExposedEvenWhenAuthenticated(String path) {
        webTestClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + generateValidToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Authenticated non-GET request to health endpoint is blocked with 404")
    void authenticatedPostToHealthEndpointReturnsNotFound() {
        webTestClient.post()
                .uri("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + generateValidToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/workflows/actuator/env",
            "/auth/actuator/env",
            "/plans/actuator/env",
            "/api/v1/unknown/actuator/env"
    })
    @DisplayName("Unmapped downstream actuator prefixes do not match any Gateway route (404)")
    void gatewayDoesNotRouteUnmappedActuatorPrefixes(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/workflows/actuator/env",
            "/api/v1/plans/actuator/env"
    })
    @DisplayName("Anonymous requests under routed prefixes containing /actuator are rejected with 401")
    void anonymousRequestsUnderRoutedPrefixesWithActuatorAreUnauthorized(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
