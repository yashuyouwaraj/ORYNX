package com.orynx.orchestrator.resilience.metrics;

import com.orynx.orchestrator.resilience.model.ExperimentResult;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded-cardinality Micrometer metrics for resilience failure experiments.
 * Pre-registers all series at startup to guarantee baseline metric presence.
 * Never tags metrics with high-cardinality IDs (experimentId, workflowId, resourceName).
 */
@Component
public class ResilienceMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeExperiments = new AtomicInteger(0);

    public ResilienceMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Pre-register experiment counters for all scenario x outcome pairs
        for (ScenarioType scenario : ScenarioType.values()) {
            String scenarioTag = normalize(scenario.name());
            for (ExperimentResult.Outcome outcome : ExperimentResult.Outcome.values()) {
                experimentCounter(scenarioTag, normalize(outcome.name()));
            }
            // Pre-register recovery timers
            recoveryTimer(scenarioTag);
            // Pre-register cleanup failure counters
            cleanupFailureCounter(scenarioTag);
            // Pre-register injection counters
            injectionCounter(scenarioTag, "success");
            injectionCounter(scenarioTag, "failure");
        }

        // Active experiments gauge
        Gauge.builder("orynx.resilience.active.experiments", activeExperiments, AtomicInteger::get)
                .description("Number of currently running resilience failure experiments")
                .register(registry);
    }

    public void recordExperimentCompleted(ScenarioType scenario, ExperimentResult.Outcome outcome) {
        String scenarioTag = normalize(scenario.name());
        String outcomeTag = normalize(outcome.name());
        experimentCounter(scenarioTag, outcomeTag).increment();
    }

    public void recordInjection(ScenarioType scenario, boolean success) {
        String scenarioTag = normalize(scenario.name());
        String statusTag = success ? "success" : "failure";
        injectionCounter(scenarioTag, statusTag).increment();
    }

    public void recordRecovery(ScenarioType scenario, long durationMs) {
        String scenarioTag = normalize(scenario.name());
        if (durationMs >= 0) {
            recoveryTimer(scenarioTag).record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordCleanupFailure(ScenarioType scenario) {
        String scenarioTag = normalize(scenario.name());
        cleanupFailureCounter(scenarioTag).increment();
    }

    public void experimentStarted() {
        activeExperiments.incrementAndGet();
    }

    public void experimentEnded() {
        activeExperiments.updateAndGet(val -> Math.max(0, val - 1));
    }

    public int getActiveExperimentsCount() {
        return activeExperiments.get();
    }

    private Counter experimentCounter(String scenario, String outcome) {
        return Counter.builder("orynx.resilience.experiments.total")
                .tag("scenario", scenario)
                .tag("outcome", outcome)
                .description("Total number of resilience failure experiments executed")
                .register(registry);
    }

    private Counter injectionCounter(String scenario, String status) {
        return Counter.builder("orynx.resilience.injections.total")
                .tag("scenario", scenario)
                .tag("status", status)
                .description("Total number of failure injections attempted")
                .register(registry);
    }

    private Counter cleanupFailureCounter(String scenario) {
        return Counter.builder("orynx.resilience.cleanup.failures.total")
                .tag("scenario", scenario)
                .description("Total number of failed resilience experiment cleanups")
                .register(registry);
    }

    private Timer recoveryTimer(String scenario) {
        return Timer.builder("orynx.resilience.recovery.duration.seconds")
                .tag("scenario", scenario)
                .description("Time taken for component to recover from injected failure")
                .register(registry);
    }

    private String normalize(String str) {
        return str == null ? "unknown" : str.trim().toLowerCase(Locale.ROOT);
    }
}
