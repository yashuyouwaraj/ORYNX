package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.InjectionLevel;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter for simulating bounded artificial network latency.
 * Explicitly classified as SIMULATION level unless bound to an isolated network proxy.
 */
@Slf4j
@Component
public class NetworkLatencyAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final ResilienceFaultRegistry faultRegistry;

    public NetworkLatencyAdapter(@Autowired(required = false) ResilienceFaultRegistry faultRegistry) {
        this.faultRegistry = faultRegistry != null ? faultRegistry : new ResilienceFaultRegistry();
    }

    public NetworkLatencyAdapter() {
        this(null);
    }

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.NETWORK_LATENCY;
    }

    @Override
    public InjectionLevel getInjectionLevel() {
        return InjectionLevel.SIMULATION;
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
        log.info("[RESILIENCE] Injecting simulated network latency of {}ms for experiment '{}' on target '{}' (duration: {}ms)",
                latency, definition.getId(), definition.getTargetResource(), definition.getDurationMs());

        faultRegistry.activateFault(ScenarioType.NETWORK_LATENCY, definition.getId(), definition.getTargetResource(), definition.getParameters());
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && faultRegistry.isFaultActive(ScenarioType.NETWORK_LATENCY);
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Removing simulated network latency on target '{}'", definition.getTargetResource());
        faultRegistry.deactivateFault(ScenarioType.NETWORK_LATENCY, definition.getId());
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !faultRegistry.isFaultActive(ScenarioType.NETWORK_LATENCY);
    }

    @Override
    public void neutralizeLocalFault(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Neutralizing local network latency fault for experiment '{}'", definition.getId());
        faultRegistry.deactivateFault(ScenarioType.NETWORK_LATENCY, definition.getId());
        injected.set(false);
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up network latency experiment '{}'", definition.getId());
        neutralizeLocalFault(definition);
    }

    public long getArtificialLatencyMs() {
        if (!faultRegistry.isFaultActive(ScenarioType.NETWORK_LATENCY)) {
            return 0L;
        }
        return Long.parseLong(faultRegistry.getParameter(ScenarioType.NETWORK_LATENCY, "latencyMs", "0"));
    }
}
