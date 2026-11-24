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
 * Adapter for executing scoped Orchestrator interruption experiments.
 * Integrates with ResilienceFaultRegistry to simulate leader lock loss and startup reconciliation.
 */
@Slf4j
@Component
public class OrchestratorInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final ResilienceFaultRegistry faultRegistry;

    public OrchestratorInterruptionAdapter(@Autowired(required = false) ResilienceFaultRegistry faultRegistry) {
        this.faultRegistry = faultRegistry != null ? faultRegistry : new ResilienceFaultRegistry();
    }

    public OrchestratorInterruptionAdapter() {
        this(null);
    }

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.ORCHESTRATOR_INTERRUPTION;
    }

    @Override
    public InjectionLevel getInjectionLevel() {
        return InjectionLevel.SIMULATION;
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
        log.info("[RESILIENCE] Injecting scoped Orchestrator interruption for experiment '{}' on target '{}' (duration: {}ms)",
                definition.getId(), definition.getTargetResource(), definition.getDurationMs());

        faultRegistry.activateFault(ScenarioType.ORCHESTRATOR_INTERRUPTION, definition.getId(), definition.getTargetResource(), definition.getParameters());
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && faultRegistry.isFaultActive(ScenarioType.ORCHESTRATOR_INTERRUPTION);
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Orchestrator operations on target '{}'", definition.getTargetResource());
        faultRegistry.deactivateFault(ScenarioType.ORCHESTRATOR_INTERRUPTION, definition.getId());
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !faultRegistry.isFaultActive(ScenarioType.ORCHESTRATOR_INTERRUPTION);
    }

    @Override
    public void neutralizeLocalFault(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Neutralizing local Orchestrator fault for experiment '{}'", definition.getId());
        faultRegistry.deactivateFault(ScenarioType.ORCHESTRATOR_INTERRUPTION, definition.getId());
        injected.set(false);
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Orchestrator interruption experiment '{}'", definition.getId());
        neutralizeLocalFault(definition);
    }

    public boolean isLeaderLockLost() {
        String mode = faultRegistry.getParameter(ScenarioType.ORCHESTRATOR_INTERRUPTION, "mode", "LEADER_LOCK_LOST");
        return faultRegistry.isFaultActive(ScenarioType.ORCHESTRATOR_INTERRUPTION) && "LEADER_LOCK_LOST".equalsIgnoreCase(mode);
    }

    public boolean isRestartInProgress() {
        String mode = faultRegistry.getParameter(ScenarioType.ORCHESTRATOR_INTERRUPTION, "mode", "LEADER_LOCK_LOST");
        return faultRegistry.isFaultActive(ScenarioType.ORCHESTRATOR_INTERRUPTION) && "RESTART_SIMULATION".equalsIgnoreCase(mode);
    }
}
