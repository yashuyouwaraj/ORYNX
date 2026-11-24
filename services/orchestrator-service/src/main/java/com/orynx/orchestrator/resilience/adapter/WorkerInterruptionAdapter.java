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
 * Adapter for executing scoped Worker interruption experiments.
 * Integrates with ResilienceFaultRegistry to simulate worker crashes, lease expiration,
 * and fencing verification.
 */
@Slf4j
@Component
public class WorkerInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final ResilienceFaultRegistry faultRegistry;

    public WorkerInterruptionAdapter(@Autowired(required = false) ResilienceFaultRegistry faultRegistry) {
        this.faultRegistry = faultRegistry != null ? faultRegistry : new ResilienceFaultRegistry();
    }

    public WorkerInterruptionAdapter() {
        this(null);
    }

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.WORKER_INTERRUPTION;
    }

    @Override
    public InjectionLevel getInjectionLevel() {
        return InjectionLevel.SIMULATION;
    }

    @Override
    public void validate(ExperimentDefinition definition) {
        String mode = definition.getParameter("mode", "WORKER_CRASH");
        if (!"WORKER_CRASH".equalsIgnoreCase(mode) && !"LEASE_EXPIRED".equalsIgnoreCase(mode) && !"HEARTBEAT_STALL".equalsIgnoreCase(mode)) {
            throw new SafetyValidationException("Unsupported worker interruption mode: " + mode);
        }
    }

    @Override
    public void inject(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Injecting scoped Worker failure on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        faultRegistry.activateFault(ScenarioType.WORKER_INTERRUPTION, definition.getTargetResource(), definition.getParameters());
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && faultRegistry.isFaultActive(ScenarioType.WORKER_INTERRUPTION);
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Worker operations on target '{}'", definition.getTargetResource());
        faultRegistry.deactivateFault(ScenarioType.WORKER_INTERRUPTION);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !faultRegistry.isFaultActive(ScenarioType.WORKER_INTERRUPTION);
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Worker interruption experiment '{}'", definition.getId());
        faultRegistry.deactivateFault(ScenarioType.WORKER_INTERRUPTION);
        injected.set(false);
    }

    public boolean isWorkerCrashed() {
        String mode = faultRegistry.getParameter(ScenarioType.WORKER_INTERRUPTION, "mode", "WORKER_CRASH");
        return faultRegistry.isFaultActive(ScenarioType.WORKER_INTERRUPTION) && "WORKER_CRASH".equalsIgnoreCase(mode);
    }

    public boolean isLeaseExpired() {
        String mode = faultRegistry.getParameter(ScenarioType.WORKER_INTERRUPTION, "mode", "WORKER_CRASH");
        return faultRegistry.isFaultActive(ScenarioType.WORKER_INTERRUPTION) && ("LEASE_EXPIRED".equalsIgnoreCase(mode) || "HEARTBEAT_STALL".equalsIgnoreCase(mode));
    }
}
