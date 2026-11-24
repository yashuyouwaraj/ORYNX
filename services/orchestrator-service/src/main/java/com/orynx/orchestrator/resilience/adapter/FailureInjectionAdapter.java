package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.InjectionLevel;
import com.orynx.orchestrator.resilience.model.ScenarioType;

/**
 * Standard SPI contract for failure injection adapters.
 * Each adapter handles the mechanics of simulating or injecting a specific failure scenario
 * in an isolated environment, verifying the injection, recovering, and performing cleanup.
 */
public interface FailureInjectionAdapter {

    /**
     * The scenario type handled by this adapter.
     */
    ScenarioType getSupportedScenario();

    /**
     * The injection level capability supported by this adapter.
     */
    default InjectionLevel getInjectionLevel() {
        return InjectionLevel.APPLICATION_SCOPED;
    }

    /**
     * Scenario-specific parameter validation before execution.
     */
    void validate(ExperimentDefinition definition);

    /**
     * Injects the failure into the isolated target.
     */
    void inject(ExperimentDefinition definition);

    /**
     * Verifies that the failure was successfully injected and observable.
     */
    boolean verifyInjected(ExperimentDefinition definition);

    /**
     * Restores the target component to normal operational state.
     */
    void recover(ExperimentDefinition definition);

    /**
     * Verifies that normal operational state was restored.
     */
    boolean verifyRecovered(ExperimentDefinition definition);

    /**
     * Unconditionally neutralizes local in-memory or in-process faults belonging to this experiment.
     * This method MUST be safe to invoke unconditionally even if distributed lock ownership was lost,
     * ensuring no JVM-local fault contamination persists after experiment abandonment.
     * Must be idempotent and scoped to definition.getId().
     */
    default void neutralizeLocalFault(ExperimentDefinition definition) {
        // default no-op for adapters with no in-process state
    }

    /**
     * Performs mandatory cleanup and teardown of any ephemeral resources created for the experiment.
     */
    void cleanup(ExperimentDefinition definition);
}
