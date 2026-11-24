package com.orynx.orchestrator.resilience;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.ExperimentSafetyValidator;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentSafetyValidatorTest {

    private ExperimentSafetyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ExperimentSafetyValidator();
    }

    private ExperimentDefinition.ExperimentDefinitionBuilder validDefinition() {
        return ExperimentDefinition.builder()
                .id("exp-redis-failover-001")
                .name("Redis Failover Test")
                .scenarioType(ScenarioType.REDIS_INTERRUPTION)
                .targetComponent("redis")
                .targetResource("isolated-redis-node-1")
                .environmentTag("resilience-isolated")
                .durationMs(1000)
                .timeoutMs(5000)
                .expectedBehavior("Fail closed during outage")
                .recoveryStrategy("Restore connection");
    }

    @Test
    @DisplayName("Valid experiment definition passes validation")
    void validDefinitionPasses() {
        assertDoesNotThrow(() -> validator.validate(validDefinition().build()));
    }

    @Test
    @DisplayName("Null definition throws SafetyValidationException")
    void nullDefinitionThrows() {
        SafetyValidationException ex = assertThrows(SafetyValidationException.class, () -> validator.validate(null));
        assertTrue(ex.getMessage().contains("cannot be null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a", "ab", "invalid!id", "exp@fail", "id with spaces"})
    @DisplayName("Invalid experiment ID throws SafetyValidationException")
    void invalidExperimentIdThrows(String id) {
        ExperimentDefinition def = validDefinition().id(id).build();
        assertThrows(SafetyValidationException.class, () -> validator.validate(def));
    }

    @Test
    @DisplayName("Null scenario type throws SafetyValidationException")
    void nullScenarioTypeThrows() {
        ExperimentDefinition def = validDefinition().scenarioType(null).build();
        assertThrows(SafetyValidationException.class, () -> validator.validate(def));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "prod-cluster", "default", "shared", "live"})
    @DisplayName("Forbidden production environment throws SafetyValidationException")
    void forbiddenEnvironmentThrows(String env) {
        ExperimentDefinition def = validDefinition().environmentTag(env).build();
        SafetyValidationException ex = assertThrows(SafetyValidationException.class, () -> validator.validate(def));
        assertTrue(ex.getMessage().contains("forbidden") || ex.getMessage().contains("not in allowed"));
    }

    @Test
    @DisplayName("Non-whitelisted environment throws SafetyValidationException")
    void nonWhitelistedEnvironmentThrows() {
        ExperimentDefinition def = validDefinition().environmentTag("dev-arbitrary").build();
        SafetyValidationException ex = assertThrows(SafetyValidationException.class, () -> validator.validate(def));
        assertTrue(ex.getMessage().contains("not in allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "orynx-audit-postgres",
            "orynx-kafka",
            "orynx-audit-redis",
            "orynx-postgres",
            "localhost:5432",
            "localhost:6379",
            "docker.sock",
            "/var/run/docker.sock"
    })
    @DisplayName("Blacklisted or shared infrastructure targets throw SafetyValidationException")
    void blacklistedTargetsThrow(String blacklisted) {
        ExperimentDefinition def = validDefinition().targetResource(blacklisted).build();
        SafetyValidationException ex = assertThrows(SafetyValidationException.class, () -> validator.validate(def));
        assertTrue(ex.getMessage().contains("blacklisted") || ex.getMessage().contains("pattern"));
    }

    @Test
    @DisplayName("Duration below minimum throws SafetyValidationException")
    void durationBelowMinimumThrows() {
        ExperimentDefinition def = validDefinition().durationMs(10).build();
        assertThrows(SafetyValidationException.class, () -> validator.validate(def));
    }

    @Test
    @DisplayName("Duration above maximum throws SafetyValidationException")
    void durationAboveMaximumThrows() {
        ExperimentDefinition def = validDefinition().durationMs(200_000).build();
        assertThrows(SafetyValidationException.class, () -> validator.validate(def));
    }

    @Test
    @DisplayName("Timeout less than duration throws SafetyValidationException")
    void timeoutLessThanDurationThrows() {
        ExperimentDefinition def = validDefinition().durationMs(5000).timeoutMs(2000).build();
        assertThrows(SafetyValidationException.class, () -> validator.validate(def));
    }

    @Test
    @DisplayName("Timeout exceeding maximum allowed throws SafetyValidationException")
    void timeoutExceedingMaxThrows() {
        ExperimentDefinition def = validDefinition().durationMs(5000).timeoutMs(400_000).build();
        assertThrows(SafetyValidationException.class, () -> validator.validate(def));
    }
}
