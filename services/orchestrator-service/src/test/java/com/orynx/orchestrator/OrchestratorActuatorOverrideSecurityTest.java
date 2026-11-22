package com.orynx.orchestrator;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.endpoints.web.exposure.include=*,health,env,beans,mappings",
        "management.endpoints.web.exposure.exclude=",
        "management.endpoints.web.discovery.enabled=true",
        "management.endpoint.health.show-details=always",
        "management.endpoint.health.show-components=always",
        "management.endpoint.health.group.readiness.show-details=always",
        "management.endpoint.health.group.readiness.show-components=always"
})
@AutoConfigureMockMvc
class OrchestratorActuatorOverrideSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebEndpointProperties webEndpointProperties;

    @Autowired
    private HealthEndpointProperties healthEndpointProperties;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    private String generateValidToken() {
        Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .setSubject("user@orynx.io")
                .claim("userId", 1L)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    @DisplayName("orchestrator-service Actuator policy resists hostile environment overrides")
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
    @DisplayName("orchestrator-service health probe omits details even under hostile overrides")
    void healthProbeRemainsCleanUnderHostileOverrides() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.components").doesNotExist());
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
            "/actuator/threaddump",
            "/actuator/loggers"
    })
    @DisplayName("Anonymous non-health Actuator paths return 401 and authenticated paths return 404 under hostile overrides")
    void sensitiveEndpointsRemainBlockedUnderHostileOverrides(String path) throws Exception {
        URI uri = URI.create("http://localhost" + path);
        mockMvc.perform(get(uri))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + generateValidToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /actuator/health returns 401 anonymously and 404 when authenticated under hostile overrides")
    void postHealthRemainsBlockedUnderHostileOverrides() throws Exception {
        mockMvc.perform(post("/actuator/health"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + generateValidToken()))
                .andExpect(status().isNotFound());
    }
}
