package com.orynx.execution;

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
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

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
class ExecutionActuatorOverrideSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebEndpointProperties webEndpointProperties;

    @Autowired
    private HealthEndpointProperties healthEndpointProperties;

    @Test
    @DisplayName("Hostile environment overrides cannot reopen Actuator endpoints or health details")
    void hostileOverridesAreRejectedAtRuntime() {
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
    @DisplayName("Health probe still returns 200 UP without details even under hostile overrides")
    void healthStillSucceedsWithoutDetailsUnderOverrides() throws Exception {
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
            "/actuator;v=1/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/loggers"
    })
    @DisplayName("Sensitive Actuator endpoints remain 404 even when wildcard or list exposure overrides are set")
    void sensitiveEndpointsRemainBlockedUnderHostileOverrides(String path) throws Exception {
        mockMvc.perform(get(URI.create("http://localhost" + path)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /actuator/health returns 404 even under hostile overrides")
    void postHealthRemainsBlockedUnderHostileOverrides() throws Exception {
        mockMvc.perform(post("/actuator/health"))
                .andExpect(status().isNotFound());
    }
}
