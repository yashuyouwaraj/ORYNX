package com.orynx.orchestrator.resilience.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Outcome and details of an executed resilience experiment.
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class ExperimentResult {

    public enum Outcome {
        PASS,
        FAIL,
        ABORTED,
        TIMED_OUT,
        INCONCLUSIVE,
        CLEANUP_FAILED
    }

    private final String experimentId;
    @Builder.Default
    private final InjectionLevel injectionLevel = InjectionLevel.APPLICATION_SCOPED;
    private final Outcome outcome;
    private final String message;
    private final Instant startTime;
    private final Instant endTime;
    private final long failureDurationMs;
    private final long recoveryDurationMs;
    @Builder.Default
    private final List<String> observedBehaviors = Collections.emptyList();
    @Builder.Default
    private final List<String> verificationErrors = Collections.emptyList();
    private final boolean cleanupSuccessful;
    @Builder.Default
    private final Map<String, Object> metricsSnapshot = Collections.emptyMap();

    public boolean isSuccessful() {
        return outcome == Outcome.PASS && cleanupSuccessful && (verificationErrors == null || verificationErrors.isEmpty());
    }

    public List<String> getObservedBehaviors() {
        return observedBehaviors != null ? Collections.unmodifiableList(observedBehaviors) : Collections.emptyList();
    }

    public List<String> getVerificationErrors() {
        return verificationErrors != null ? Collections.unmodifiableList(verificationErrors) : Collections.emptyList();
    }

    public Map<String, Object> getMetricsSnapshot() {
        return metricsSnapshot != null ? Collections.unmodifiableMap(metricsSnapshot) : Collections.emptyMap();
    }
}
