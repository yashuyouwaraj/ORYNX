package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapter for simulating bounded artificial network latency and connection degradation
 * between isolated test components.
 */
@Slf4j
@Component
public class NetworkLatencyAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final AtomicLong artificialLatencyMs = new AtomicLong(0);

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.NETWORK_LATENCY;
    }

    @Override
    public void validate(ExperimentDefinition definition) {
        long latency = Long.parseLong(definition.getParameter("latencyMs", "500"));
        if (latency < 10 || latency > 10000) {
            throw new SafetyValidationException("Artificial latency must be between 10ms and 10000ms: " + latency);
        }
    }

    @Override
    public void inject(ExperimentDefinition definition) {
        long latency = Long.parseLong(definition.getParameter("latencyMs", "500"));
        log.info("[RESILIENCE] Injecting network latency of {}ms on target '{}' (duration: {}ms)",
                latency, definition.getTargetResource(), definition.getDurationMs());

        artificialLatencyMs.set(latency);
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && artificialLatencyMs.get() > 0;
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Removing artificial network latency on target '{}'", definition.getTargetResource());
        artificialLatencyMs.set(0);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return artificialLatencyMs.get() == 0;
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up network latency experiment '{}'", definition.getId());
        artificialLatencyMs.set(0);
        injected.set(false);
    }

    public long getArtificialLatencyMs() {
        return artificialLatencyMs.get();
    }
}
