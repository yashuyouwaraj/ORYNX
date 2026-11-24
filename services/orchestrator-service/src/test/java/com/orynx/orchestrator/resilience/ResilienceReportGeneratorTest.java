package com.orynx.orchestrator.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ExperimentResult;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.reporting.ResilienceReportGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilienceReportGeneratorTest {

    private ResilienceReportGenerator generator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        generator = new ResilienceReportGenerator();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Markdown report contains all 17 mandatory audit sections")
    void markdownReportContainsAll17Sections() {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-redis-001")
                .name("Redis Resilience Test")
                .scenarioType(ScenarioType.REDIS_INTERRUPTION)
                .targetComponent("redis")
                .targetResource("isolated-redis-1")
                .environmentTag("resilience-isolated")
                .durationMs(500)
                .timeoutMs(2000)
                .parameters(Map.of("policy", "FAIL_CLOSED"))
                .expectedBehavior("Admission controller returns 503")
                .recoveryStrategy("Redis reconnects")
                .build();

        ExperimentResult res = ExperimentResult.builder()
                .experimentId("exp-redis-001")
                .outcome(ExperimentResult.Outcome.PASS)
                .message("Passed all assertions")
                .startTime(Instant.now().minusSeconds(2))
                .endTime(Instant.now())
                .failureDurationMs(500)
                .recoveryDurationMs(20)
                .observedBehaviors(List.of("Safety verified", "Failure injected", "Recovered in 20ms"))
                .verificationErrors(List.of())
                .cleanupSuccessful(true)
                .build();

        String md = generator.generateMarkdownReport(def, res);

        List<String> requiredSections = List.of(
                "## 1. Experiment ID",
                "## 2. Scenario",
                "## 3. Environment",
                "## 4. Target",
                "## 5. Preconditions",
                "## 6. Injection Parameters",
                "## 7. Start Timestamp",
                "## 8. Failure Duration",
                "## 9. Observed Behavior",
                "## 10. Workflow Impact",
                "## 11. Recovery Duration",
                "## 12. Recovery Validation",
                "## 13. Metrics and Alerts",
                "## 14. Cleanup Status",
                "## 15. Result",
                "## 16. Failure Details",
                "## 17. Reproduction Instructions"
        );

        for (String section : requiredSections) {
            assertTrue(md.contains(section), "Markdown report missing section: " + section);
        }

        assertTrue(md.contains("exp-redis-001"));
        assertTrue(md.contains("REDIS_INTERRUPTION"));
        assertTrue(md.contains("CLEANUP SUCCESSFUL"));
        assertTrue(md.contains("**PASS**"));
    }

    @Test
    @DisplayName("JSON report produces valid JSON with all required keys")
    void jsonReportValid() throws Exception {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-kafka-001")
                .name("Kafka Broker Down")
                .scenarioType(ScenarioType.KAFKA_INTERRUPTION)
                .targetComponent("kafka")
                .targetResource("isolated-kafka-node-1")
                .environmentTag("resilience-isolated")
                .durationMs(200)
                .timeoutMs(1000)
                .build();

        ExperimentResult res = ExperimentResult.builder()
                .experimentId("exp-kafka-001")
                .outcome(ExperimentResult.Outcome.PASS)
                .message("Success")
                .startTime(Instant.now().minusSeconds(1))
                .endTime(Instant.now())
                .failureDurationMs(200)
                .recoveryDurationMs(15)
                .cleanupSuccessful(true)
                .build();

        String json = generator.generateJsonReport(def, res);
        JsonNode root = objectMapper.readTree(json);

        assertEquals("exp-kafka-001", root.path("experimentId").asText());
        assertEquals("KAFKA_INTERRUPTION", root.path("scenario").asText());
        assertEquals("resilience-isolated", root.path("environment").asText());
        assertEquals("PASS", root.path("outcome").asText());
        assertTrue(root.path("cleanupSuccessful").asBoolean());
    }
}
