package com.orynx.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExecutionServiceApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebEndpointProperties webEndpointProperties;

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
	@DisplayName("execution-service health probes return 200 without exposing details")
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
	@DisplayName("execution-service does not expose sensitive Actuator endpoints")
	void sensitiveActuatorEndpointsReturnNotFound(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isNotFound());
	}
}
