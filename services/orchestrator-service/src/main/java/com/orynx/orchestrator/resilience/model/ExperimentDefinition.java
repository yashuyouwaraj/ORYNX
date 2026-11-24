package com.orynx.orchestrator.resilience.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable definition of a resilience experiment.
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class ExperimentDefinition {

    private final String id;
    private final String name;
    private final ScenarioType scenarioType;
    @Builder.Default
    private final InjectionLevel injectionLevel = InjectionLevel.APPLICATION_SCOPED;
    private final String targetComponent;
    private final String targetResource;
    private final String environmentTag;
    private final long durationMs;
    private final long timeoutMs;
    @Builder.Default
    private final Map<String, String> parameters = Collections.emptyMap();
    private final String expectedBehavior;
    private final String recoveryStrategy;
    @Builder.Default
    private final boolean abortOnFailure = true;

    public Map<String, String> getParameters() {
        return parameters != null ? Collections.unmodifiableMap(parameters) : Collections.emptyMap();
    }

    public String getParameter(String key, String defaultValue) {
        if (parameters == null) {
            return defaultValue;
        }
        return parameters.getOrDefault(key, defaultValue);
    }
}
