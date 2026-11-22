package com.orynx.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebEndpointProperties webEndpointProperties;

    @Autowired
    private ServerProperties serverProperties;

    @Test
    @DisplayName("auth-service exposes only health endpoint with no wildcard")
    void authServiceExposesOnlyHealth() {
        assertThat(serverProperties.getPort()).isEqualTo(8081);
        assertThat(webEndpointProperties.getExposure().getInclude())
                .containsExactly("health")
                .doesNotContain("*");
        assertThat(webEndpointProperties.getExposure().getExclude())
                .contains("env", "configprops", "beans", "mappings", "heapdump",
                          "threaddump", "loggers", "shutdown", "conditions", "scheduledtasks", "info");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    })
    @DisplayName("Public health endpoints return 200 UP without sensitive component details")
    void publicHealthEndpointsArePermittedWithoutDetails(String path) throws Exception {
        mockMvc.perform(get(path))
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
            "/actuator/loggers",
            "/actuator/conditions",
            "/actuator/scheduledtasks",
            "/actuator/info"
    })
    @DisplayName("Anonymous requests to non-health Actuator paths are rejected")
    void anonymousRequestsToActuatorAreRejected(String path) throws Exception {
        int statusCode = mockMvc.perform(get(path))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(statusCode).isIn(401, 403);
    }

    @Test
    @DisplayName("Anonymous non-GET requests to /actuator/health are rejected")
    void anonymousPostToHealthEndpointIsRejected() throws Exception {
        int statusCode = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/actuator/health"))
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
            "/actuator/loggers",
            "/actuator/conditions",
            "/actuator/scheduledtasks",
            "/actuator/info"
    })
    @WithMockUser(username = "user@orynx.io")
    @DisplayName("Authenticated requests confirm sensitive Actuator endpoints are disabled (404)")
    void sensitiveActuatorEndpointsReturnNotFoundEvenWhenAuthenticated(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "user@orynx.io")
    @DisplayName("Authenticated POST /actuator/health returns 404")
    void authenticatedPostToHealthEndpointReturnsNotFound() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("All five backend services restrict Actuator exposure to health and hide health details")
    void allFiveServicesHardenActuatorConfiguration() throws IOException {
        Path projectRoot = Path.of("../..").toAbsolutePath().normalize();
        String[] services = {
                "gateway-service",
                "auth-service",
                "orchestrator-service",
                "execution-service",
                "planning-service"
        };

        for (String service : services) {
            Path propsPath = projectRoot.resolve("services/" + service + "/src/main/resources/application.properties");
            assertThat(Files.exists(propsPath)).as("Properties file must exist for %s", service).isTrue();

            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(propsPath)) {
                props.load(in);
            }

            assertThat(props.getProperty("management.endpoints.web.exposure.include"))
                    .as("%s must expose only health", service)
                    .isEqualTo("health");
            assertThat(props.getProperty("management.endpoints.web.exposure.exclude"))
                    .as("%s must explicitly exclude sensitive diagnostic endpoints", service)
                    .contains("env", "configprops", "beans", "heapdump", "threaddump", "loggers", "shutdown");
            assertThat(props.getProperty("management.endpoint.health.show-details"))
                    .as("%s must hide health details", service)
                    .isEqualTo("never");
            assertThat(props.getProperty("management.endpoint.health.show-components"))
                    .as("%s must hide health components", service)
                    .isEqualTo("never");
            assertThat(props.getProperty("management.endpoint.health.probes.enabled"))
                    .as("%s must enable Kubernetes health probes", service)
                    .isEqualTo("true");
            assertThat(props.getProperty("management.endpoints.web.discovery.enabled"))
                    .as("%s must disable Actuator discovery index", service)
                    .isEqualTo("false");
            assertThat(props.getProperty("server.address"))
                    .as("%s must not pin container interface to loopback", service)
                    .isNull();
        }
    }
}
