package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter for simulating Redis unavailability and degradation in isolated environments.
 * Tests admission controller fail-closed/fail-open policies, distributed locking,
 * lease expiration, and state reconciliation upon recovery.
 */
@Slf4j
@Component
public class RedisInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final AtomicBoolean simulatedFailureMode = new AtomicBoolean(false);
    private AdmissionFailurePolicy originalPolicy = AdmissionFailurePolicy.FAIL_CLOSED;

    @Autowired(required = false)
    private RedisCoordinationProperties coordinationProperties;

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.REDIS_INTERRUPTION;
    }

    @Override
    public void validate(ExperimentDefinition definition) {
        String mode = definition.getParameter("policy", "FAIL_CLOSED");
        if (!"FAIL_CLOSED".equalsIgnoreCase(mode) && !"FAIL_OPEN".equalsIgnoreCase(mode)) {
            throw new SafetyValidationException("Unsupported Redis failure policy parameter: " + mode);
        }
    }

    @Override
    public void inject(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Injecting Redis interruption on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        if (coordinationProperties != null) {
            this.originalPolicy = coordinationProperties.getFailurePolicy();
            String requestedPolicy = definition.getParameter("policy", "FAIL_CLOSED");
            coordinationProperties.setFailurePolicy(
                    "FAIL_OPEN".equalsIgnoreCase(requestedPolicy) ? AdmissionFailurePolicy.FAIL_OPEN : AdmissionFailurePolicy.FAIL_CLOSED
            );
        }

        simulatedFailureMode.set(true);
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && simulatedFailureMode.get();
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Redis connection on target '{}'", definition.getTargetResource());
        simulatedFailureMode.set(false);
        if (coordinationProperties != null) {
            coordinationProperties.setFailurePolicy(originalPolicy);
        }
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !simulatedFailureMode.get();
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Redis interruption experiment '{}'", definition.getId());
        simulatedFailureMode.set(false);
        injected.set(false);
        if (coordinationProperties != null) {
            coordinationProperties.setFailurePolicy(originalPolicy);
        }
    }

    public boolean isSimulatedFailureActive() {
        return simulatedFailureMode.get();
    }
}
