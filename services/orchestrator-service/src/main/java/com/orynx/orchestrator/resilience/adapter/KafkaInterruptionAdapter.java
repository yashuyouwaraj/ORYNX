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
 * Adapter for executing safely scoped Kafka failure experiments.
 * Integrates with ResilienceFaultRegistry to simulate publisher drops and consumer pauses
 * in a controlled application context.
 */
@Slf4j
@Component
public class KafkaInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final ResilienceFaultRegistry faultRegistry;

    public KafkaInterruptionAdapter(@Autowired(required = false) ResilienceFaultRegistry faultRegistry) {
        this.faultRegistry = faultRegistry != null ? faultRegistry : new ResilienceFaultRegistry();
    }

    public KafkaInterruptionAdapter() {
        this(null);
    }

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.KAFKA_INTERRUPTION;
    }

    @Override
    public InjectionLevel getInjectionLevel() {
        return InjectionLevel.APPLICATION_SCOPED;
    }

    @Override
    public void validate(ExperimentDefinition definition) {
        String mode = definition.getParameter("mode", "PRODUCER_DROP");
        if (!"PRODUCER_DROP".equalsIgnoreCase(mode) && !"CONSUMER_PAUSE".equalsIgnoreCase(mode) && !"BROKER_UNREACHABLE".equalsIgnoreCase(mode)) {
            throw new SafetyValidationException("Unsupported Kafka interruption mode: " + mode);
        }
    }

    @Override
    public void inject(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Injecting scoped Kafka failure on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        faultRegistry.activateFault(ScenarioType.KAFKA_INTERRUPTION, definition.getTargetResource(), definition.getParameters());
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && faultRegistry.isFaultActive(ScenarioType.KAFKA_INTERRUPTION);
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Kafka operations on target '{}'", definition.getTargetResource());
        faultRegistry.deactivateFault(ScenarioType.KAFKA_INTERRUPTION);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !faultRegistry.isFaultActive(ScenarioType.KAFKA_INTERRUPTION);
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Kafka interruption experiment '{}'", definition.getId());
        faultRegistry.deactivateFault(ScenarioType.KAFKA_INTERRUPTION);
        injected.set(false);
    }

    public boolean isDropPublications() {
        String mode = faultRegistry.getParameter(ScenarioType.KAFKA_INTERRUPTION, "mode", "PRODUCER_DROP");
        return faultRegistry.isFaultActive(ScenarioType.KAFKA_INTERRUPTION) && ("PRODUCER_DROP".equalsIgnoreCase(mode) || "BROKER_UNREACHABLE".equalsIgnoreCase(mode));
    }

    public boolean isPauseConsumers() {
        String mode = faultRegistry.getParameter(ScenarioType.KAFKA_INTERRUPTION, "mode", "PRODUCER_DROP");
        return faultRegistry.isFaultActive(ScenarioType.KAFKA_INTERRUPTION) && "CONSUMER_PAUSE".equalsIgnoreCase(mode);
    }
}
