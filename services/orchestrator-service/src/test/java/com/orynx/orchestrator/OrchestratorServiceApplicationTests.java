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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrchestratorServiceApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebEndpointProperties webEndpointProperties;

	@Value("${jwt.secret:}")
	private String jwtSecret;

	@Test
	void contextLoads() {
		assertThat(webEndpointProperties.getExposure().getInclude())
				.containsExactly("health")
				.doesNotContain("*");
		assertThat(webEndpointProperties.getExposure().getExclude())
				.contains("env", "configprops", "beans", "mappings", "heapdump", "threaddump", "loggers", "shutdown");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"/actuator/health",
			"/actuator/health/liveness",
			"/actuator/health/readiness"
	})
	@DisplayName("orchestrator-service health probes return 200 UP without exposing details")
	void healthEndpointsReturnStatusWithoutDetails(String path) throws Exception {
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
			"/actuator/configprops",
			"/actuator/beans",
			"/actuator/mappings",
			"/actuator/heapdump",
			"/actuator/threaddump",
			"/actuator/loggers",
			"/actuator/conditions",
			"/actuator/scheduledtasks"
	})
	@DisplayName("orchestrator-service rejects anonymous requests to non-health Actuator paths and disables them")
	void sensitiveActuatorEndpointsAreProtectedAndUnexposed(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isUnauthorized());

		if (jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32) {
			Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
			String token = Jwts.builder()
					.setSubject("user@orynx.io")
					.claim("userId", 1L)
					.setIssuedAt(new Date())
					.setExpiration(new Date(System.currentTimeMillis() + 3600000L))
					.signWith(key, SignatureAlgorithm.HS256)
					.compact();

			mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isNotFound());
		}
	}
}
