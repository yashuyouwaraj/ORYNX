package com.orynx.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

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
class AuthActuatorOverrideSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebEndpointProperties webEndpointProperties;

    @Autowired
    private HealthEndpointProperties healthEndpointProperties;

    @Test
    @DisplayName("auth-service Actuator policy resists hostile environment overrides")
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
    @DisplayName("auth-service health probe omits details even under hostile overrides")
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
            "/ACTUATOR/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/loggers"
    })
    @DisplayName("Anonymous requests to non-health Actuator paths remain rejected under hostile overrides")
    void anonymousNonHealthRequestsRejectedUnderHostileOverrides(String path) throws Exception {
        int statusCode = mockMvc.perform(get(path))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(statusCode).isIn(401, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/env",
            "/actuator/env/",
            "/ACTUATOR/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/loggers"
    })
    @WithMockUser(username = "admin@orynx.io")
    @DisplayName("Sensitive Actuator endpoints remain 404 to authenticated callers under hostile overrides")
    void sensitiveEndpointsRemainBlockedUnderHostileOverrides(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin@orynx.io")
    @DisplayName("Authenticated POST /actuator/health returns 404 under hostile overrides")
    void authenticatedPostHealthReturnsNotFoundUnderHostileOverrides() throws Exception {
        mockMvc.perform(post("/actuator/health"))
                .andExpect(status().isNotFound());
    }
}
