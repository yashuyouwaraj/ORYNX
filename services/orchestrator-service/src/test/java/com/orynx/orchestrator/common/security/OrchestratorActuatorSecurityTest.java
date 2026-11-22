package com.orynx.orchestrator.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrchestratorActuatorSecurityTest {

    private static final String TEST_SECRET = "testSecretKeyForJwtSigning1234567890!@#$";

    private MockMvc mockMvc;

    @RestController
    static class StubController {
        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @GetMapping("/actuator/health/liveness")
        String liveness() {
            return "UP";
        }

        @GetMapping("/actuator/health/readiness")
        String readiness() {
            return "UP";
        }

        @GetMapping("/actuator/env")
        String env() {
            return "sensitive";
        }

        @GetMapping("/api/v1/workflows")
        String workflows() {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        OrchestratorJwtService jwtService = new OrchestratorJwtService(TEST_SECRET);
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new UserContextFilter(jwtService))
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private String generateToken() {
        Key key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .setSubject("user@orynx.io")
                .claim("userId", 42L)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    })
    @DisplayName("UserContextFilter permits public health and probe endpoints anonymously")
    void userContextFilterPermitsPublicHealthProbes(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk());
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
            "/actuator/loggers",
            "/actuator/conditions",
            "/actuator/scheduledtasks",
            "/actuator/shutdown",
            "/actuator/health/custom"
    })
    @DisplayName("UserContextFilter rejects anonymous requests to non-health Actuator paths with 401")
    void userContextFilterRejectsAnonymousSensitiveActuatorRequests(String path) throws Exception {
        mockMvc.perform(get(URI.create("http://localhost" + path)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/env",
            "/actuator/env/",
            "//actuator/env",
            "/ACTUATOR/env",
            "/actuator;param=1/env",
            "/actuator/health/custom"
    })
    @DisplayName("UserContextFilter blocks authenticated requests to non-health Actuator paths with 404")
    void userContextFilterBlocksAuthenticatedSensitiveActuatorRequests(String path) throws Exception {
        mockMvc.perform(get(URI.create("http://localhost" + path))
                        .header("Authorization", "Bearer " + generateToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("UserContextFilter rejects anonymous POST /actuator/health with 401 and blocks authenticated POST with 404")
    void userContextFilterBlocksNonGetHealthRequests() throws Exception {
        mockMvc.perform(post("/actuator/health"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/actuator/health")
                        .header("Authorization", "Bearer " + generateToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("UserContextFilter rejects spoofed X-User-Id header on /actuator/env")
    void userContextFilterRejectsSpoofedHeadersOnActuator() throws Exception {
        mockMvc.perform(get("/actuator/env")
                        .header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated application API calls remain permitted through UserContextFilter")
    void authenticatedApplicationApiRemainsPermitted() throws Exception {
        mockMvc.perform(get("/api/v1/workflows")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + generateToken()))
                .andExpect(status().isOk());
    }
}
