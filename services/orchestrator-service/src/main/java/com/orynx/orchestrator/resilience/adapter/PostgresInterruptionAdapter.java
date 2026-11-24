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
 * Adapter for executing scoped PostgreSQL failure experiments.
 * Integrates with ResilienceFaultRegistry to simulate transaction aborts and connection timeouts
 * without interrupting the shared database instance.
 */
@Slf4j
@Component
public class PostgresInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final ResilienceFaultRegistry faultRegistry;

    public PostgresInterruptionAdapter(@Autowired(required = false) ResilienceFaultRegistry faultRegistry) {
        this.faultRegistry = faultRegistry != null ? faultRegistry : new ResilienceFaultRegistry();
    }

    public PostgresInterruptionAdapter() {
        this(null);
    }

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.POSTGRES_INTERRUPTION;
    }

    @Override
    public InjectionLevel getInjectionLevel() {
        return InjectionLevel.SIMULATION;
    }

    @Override
    public void validate(ExperimentDefinition definition) {
        String mode = definition.getParameter("mode", "TRANSACTION_ABORT");
        if (!"TRANSACTION_ABORT".equalsIgnoreCase(mode) && !"CONNECTION_DOWN".equalsIgnoreCase(mode)) {
            throw new SafetyValidationException("Unsupported Postgres interruption mode: " + mode);
        }
    }

    @Override
    public void inject(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Injecting scoped PostgreSQL failure on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        faultRegistry.activateFault(ScenarioType.POSTGRES_INTERRUPTION, definition.getTargetResource(), definition.getParameters());
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && faultRegistry.isFaultActive(ScenarioType.POSTGRES_INTERRUPTION);
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering PostgreSQL operations on target '{}'", definition.getTargetResource());
        faultRegistry.deactivateFault(ScenarioType.POSTGRES_INTERRUPTION);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !faultRegistry.isFaultActive(ScenarioType.POSTGRES_INTERRUPTION);
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up PostgreSQL interruption experiment '{}'", definition.getId());
        faultRegistry.deactivateFault(ScenarioType.POSTGRES_INTERRUPTION);
        injected.set(false);
    }

    public boolean isDbDown() {
        String mode = faultRegistry.getParameter(ScenarioType.POSTGRES_INTERRUPTION, "mode", "TRANSACTION_ABORT");
        return faultRegistry.isFaultActive(ScenarioType.POSTGRES_INTERRUPTION) && "CONNECTION_DOWN".equalsIgnoreCase(mode);
    }

    public boolean isTransactionAbort() {
        String mode = faultRegistry.getParameter(ScenarioType.POSTGRES_INTERRUPTION, "mode", "TRANSACTION_ABORT");
        return faultRegistry.isFaultActive(ScenarioType.POSTGRES_INTERRUPTION) && "TRANSACTION_ABORT".equalsIgnoreCase(mode);
    }
}
