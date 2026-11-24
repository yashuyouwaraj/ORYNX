package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter for simulating execution worker crashes, heartbeat failure,
 * lease expiration, and fencing token validation.
 * Tests zombie worker recovery, task retry, and duplicate execution prevention.
 */
@Slf4j
@Component
public class WorkerInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final AtomicBoolean simulateWorkerCrash = new AtomicBoolean(false);
    private final AtomicBoolean simulateLeaseExpired = new AtomicBoolean(false);

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.WORKER_INTERRUPTION;
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
        log.info("[RESILIENCE] Injecting Worker failure on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        String mode = definition.getParameter("mode", "WORKER_CRASH");
        if ("LEASE_EXPIRED".equalsIgnoreCase(mode) || "HEARTBEAT_STALL".equalsIgnoreCase(mode)) {
            simulateLeaseExpired.set(true);
        } else {
            simulateWorkerCrash.set(true);
        }
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && (simulateWorkerCrash.get() || simulateLeaseExpired.get());
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Worker operations on target '{}'", definition.getTargetResource());
        simulateWorkerCrash.set(false);
        simulateLeaseExpired.set(false);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !simulateWorkerCrash.get() && !simulateLeaseExpired.get();
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Worker interruption experiment '{}'", definition.getId());
        simulateWorkerCrash.set(false);
        simulateLeaseExpired.set(false);
        injected.set(false);
    }

    public boolean isWorkerCrashed() {
        return simulateWorkerCrash.get();
    }

    public boolean isLeaseExpired() {
        return simulateLeaseExpired.get();
    }
}
