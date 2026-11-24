package com.orynx.orchestrator.resilience.safety;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates resilience experiment safety constraints before execution.
 * Enforces strict environment isolation, resource naming boundaries, and duration limits.
 * Prevents targeting shared infrastructure or production environments.
 */
@Slf4j
@Component
public class ExperimentSafetyValidator {

    public static final long MIN_DURATION_MS = 50L;
    public static final long MAX_DURATION_MS = 120_000L; // 2 minutes
    public static final long MAX_TIMEOUT_MS = 300_000L;  // 5 minutes

    private static final Set<String> ALLOWED_ENVIRONMENTS = Set.of(
            "test", "resilience-isolated", "staging", "isolated"
    );

    private static final Set<String> FORBIDDEN_ENVIRONMENT_SUBSTRINGS = Set.of(
            "prod", "production", "default", "shared", "main", "live"
    );

    private static final Set<String> BLACKLISTED_RESOURCES = Set.of(
            "orynx-audit-postgres",
            "orynx-kafka",
            "orynx-audit-redis",
            "orynx-postgres",
            "orynx-redis",
            "orynx-tempo",
            "orynx-prometheus",
            "orynx-grafana",
            "orynx-alertmanager",
            "orynx-otel-collector",
            "localhost:5432",
            "localhost:5439",
            "localhost:6379",
            "localhost:9092",
            "localhost:9093",
            "localhost:9090",
            "docker.sock",
            "/var/run/docker.sock"
    );

    private static final Pattern RESOURCE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_\\-\\.:]{2,63}$");
    private static final Pattern EXPERIMENT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{3,64}$");

    public void validate(ExperimentDefinition definition) {
        if (definition == null) {
            throw new SafetyValidationException("Experiment definition cannot be null");
        }

        // Validate Experiment ID
        if (definition.getId() == null || !EXPERIMENT_ID_PATTERN.matcher(definition.getId()).matches()) {
            throw new SafetyValidationException("Experiment ID must be 3-64 alphanumeric/dash/underscore characters: " + definition.getId());
        }

        // Validate Name
        if (definition.getName() == null || definition.getName().trim().isEmpty()) {
            throw new SafetyValidationException("Experiment name cannot be empty");
        }

        // Validate Scenario Type
        if (definition.getScenarioType() == null) {
            throw new SafetyValidationException("ScenarioType must be specified");
        }

        // Validate Environment Tag
        String env = definition.getEnvironmentTag();
        if (env == null || env.trim().isEmpty()) {
            throw new SafetyValidationException("Environment tag must be explicitly specified");
        }
        String normalizedEnv = env.trim().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_ENVIRONMENT_SUBSTRINGS) {
            if (normalizedEnv.contains(forbidden)) {
                throw new SafetyValidationException("Environment tag '" + env + "' contains forbidden substring: " + forbidden);
            }
        }
        if (!ALLOWED_ENVIRONMENTS.contains(normalizedEnv)) {
            throw new SafetyValidationException("Environment tag '" + env + "' is not in allowed environments list: " + ALLOWED_ENVIRONMENTS);
        }

        // Validate Target Component
        if (definition.getTargetComponent() == null || definition.getTargetComponent().trim().isEmpty()) {
            throw new SafetyValidationException("Target component cannot be empty");
        }

        // Validate Target Resource
        String targetResource = definition.getTargetResource();
        if (targetResource == null || !RESOURCE_NAME_PATTERN.matcher(targetResource).matches()) {
            throw new SafetyValidationException("Target resource must match pattern " + RESOURCE_NAME_PATTERN + ": " + targetResource);
        }
        String normalizedResource = targetResource.trim().toLowerCase(Locale.ROOT);
        for (String blacklisted : BLACKLISTED_RESOURCES) {
            if (normalizedResource.equals(blacklisted) || normalizedResource.contains(blacklisted)) {
                throw new SafetyValidationException("Target resource '" + targetResource + "' targets blacklisted/shared infrastructure: " + blacklisted);
            }
        }

        // Validate Durations
        if (definition.getDurationMs() < MIN_DURATION_MS) {
            throw new SafetyValidationException("Experiment duration " + definition.getDurationMs() + "ms is less than minimum " + MIN_DURATION_MS + "ms");
        }
        if (definition.getDurationMs() > MAX_DURATION_MS) {
            throw new SafetyValidationException("Experiment duration " + definition.getDurationMs() + "ms exceeds maximum allowed " + MAX_DURATION_MS + "ms");
        }
        if (definition.getTimeoutMs() < definition.getDurationMs()) {
            throw new SafetyValidationException("Experiment timeout " + definition.getTimeoutMs() + "ms cannot be less than failure duration " + definition.getDurationMs() + "ms");
        }
        if (definition.getTimeoutMs() > MAX_TIMEOUT_MS) {
            throw new SafetyValidationException("Experiment timeout " + definition.getTimeoutMs() + "ms exceeds maximum allowed " + MAX_TIMEOUT_MS + "ms");
        }

        log.info("Experiment definition '{}' passed safety validation (target={}, env={}, duration={}ms)",
                definition.getId(), definition.getTargetResource(), definition.getEnvironmentTag(), definition.getDurationMs());
    }
}
