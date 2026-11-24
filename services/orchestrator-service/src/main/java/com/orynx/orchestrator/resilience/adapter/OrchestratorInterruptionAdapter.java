package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter for simulating orchestrator service interruption, leader lock loss,
 * graceful restart, and startup state reconciliation.
 */
@Slf4j
@Component
public class OrchestratorInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final AtomicBoolean leaderLockLost = new AtomicBoolean(false);
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.ORCHESTRATOR_INTERRUPTION;
    }

    @Override
    public void validate(ExperimentDefinition definition) {
        String mode = definition.getParameter("mode", "LEADER_LOCK_LOST");
        if (!"LEADER_LOCK_LOST".equalsIgnoreCase(mode) && !"RESTART_SIMULATION".equalsIgnoreCase(mode)) {
            throw new SafetyValidationException("Unsupported orchestrator interruption mode: " + mode);
        }
    }

    @Override
    public void inject(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Injecting Orchestrator interruption on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        String mode = definition.getParameter("mode", "LEADER_LOCK_LOST");
        if ("RESTART_SIMULATION".equalsIgnoreCase(mode)) {
            restartInProgress.set(true);
        } else {
            leaderLockLost.set(true);
        }
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && (leaderLockLost.get() || restartInProgress.get());
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Orchestrator operations on target '{}'", definition.getTargetResource());
        leaderLockLost.set(false);
        restartInProgress.set(false);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !leaderLockLost.get() && !restartInProgress.get();
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Orchestrator interruption experiment '{}'", definition.getId());
        leaderLockLost.set(false);
        restartInProgress.set(false);
        injected.set(false);
    }

    public boolean isLeaderLockLost() {
        return leaderLockLost.get();
    }

    public boolean isRestartInProgress() {
        return restartInProgress.get();
    }
}
