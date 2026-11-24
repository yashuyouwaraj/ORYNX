package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter for simulating PostgreSQL transaction timeouts, connection drops,
 * and database unavailability in an isolated environment.
 * Tests transaction rollbacks, admission rollback on DB error, and state integrity.
 */
@Slf4j
@Component
public class PostgresInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final AtomicBoolean simulateDbDown = new AtomicBoolean(false);
    private final AtomicBoolean simulateTransactionAbort = new AtomicBoolean(false);

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.POSTGRES_INTERRUPTION;
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
        log.info("[RESILIENCE] Injecting PostgreSQL failure on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        String mode = definition.getParameter("mode", "TRANSACTION_ABORT");
        if ("CONNECTION_DOWN".equalsIgnoreCase(mode)) {
            simulateDbDown.set(true);
        } else {
            simulateTransactionAbort.set(true);
        }
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && (simulateDbDown.get() || simulateTransactionAbort.get());
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering PostgreSQL operations on target '{}'", definition.getTargetResource());
        simulateDbDown.set(false);
        simulateTransactionAbort.set(false);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !simulateDbDown.get() && !simulateTransactionAbort.get();
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up PostgreSQL interruption experiment '{}'", definition.getId());
        simulateDbDown.set(false);
        simulateTransactionAbort.set(false);
        injected.set(false);
    }

    public boolean isDbDown() {
        return simulateDbDown.get();
    }

    public boolean isTransactionAbort() {
        return simulateTransactionAbort.get();
    }
}
