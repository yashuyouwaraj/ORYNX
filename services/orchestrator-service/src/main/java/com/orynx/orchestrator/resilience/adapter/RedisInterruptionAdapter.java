package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter for executing safely scoped Redis interruption failure experiments.
 * Injects scoped faults via ResilienceFaultRegistry so live admission controllers
 * observe the fault without mutating global Spring singleton configuration.
 */
@Slf4j
@Component
public class RedisInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final ResilienceFaultRegistry faultRegistry;

    public RedisInterruptionAdapter(@Autowired(required = false) ResilienceFaultRegistry faultRegistry) {
        this.faultRegistry = faultRegistry != null ? faultRegistry : new ResilienceFaultRegistry();
    }

    public RedisInterruptionAdapter() {
        this(null);
    }

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
        log.info("[RESILIENCE] Injecting scoped Redis interruption on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        faultRegistry.activateFault(ScenarioType.REDIS_INTERRUPTION, definition.getTargetResource(), definition.getParameters());
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && faultRegistry.isRedisFaultActive();
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Redis connection on target '{}'", definition.getTargetResource());
        faultRegistry.deactivateFault(ScenarioType.REDIS_INTERRUPTION);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !faultRegistry.isRedisFaultActive();
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Redis interruption experiment '{}'", definition.getId());
        faultRegistry.deactivateFault(ScenarioType.REDIS_INTERRUPTION);
        injected.set(false);
    }

    public boolean isSimulatedFailureActive() {
        return faultRegistry.isRedisFaultActive();
    }

    public ResilienceFaultRegistry getFaultRegistry() {
        return faultRegistry;
    }
}
