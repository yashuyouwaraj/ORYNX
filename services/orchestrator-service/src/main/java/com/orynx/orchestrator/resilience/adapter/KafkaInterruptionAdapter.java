package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter for simulating Kafka broker interruptions, producer dispatch drops,
 * and consumer pause/lag scenarios in an isolated environment.
 */
@Slf4j
@Component
public class KafkaInterruptionAdapter implements FailureInjectionAdapter {

    private final AtomicBoolean injected = new AtomicBoolean(false);
    private final AtomicBoolean dropPublications = new AtomicBoolean(false);
    private final AtomicBoolean pauseConsumers = new AtomicBoolean(false);

    @Override
    public ScenarioType getSupportedScenario() {
        return ScenarioType.KAFKA_INTERRUPTION;
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
        log.info("[RESILIENCE] Injecting Kafka failure on target '{}' (duration: {}ms)",
                definition.getTargetResource(), definition.getDurationMs());

        String mode = definition.getParameter("mode", "PRODUCER_DROP");
        if ("CONSUMER_PAUSE".equalsIgnoreCase(mode)) {
            pauseConsumers.set(true);
        } else {
            dropPublications.set(true);
        }
        injected.set(true);
    }

    @Override
    public boolean verifyInjected(ExperimentDefinition definition) {
        return injected.get() && (dropPublications.get() || pauseConsumers.get());
    }

    @Override
    public void recover(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Recovering Kafka operations on target '{}'", definition.getTargetResource());
        dropPublications.set(false);
        pauseConsumers.set(false);
    }

    @Override
    public boolean verifyRecovered(ExperimentDefinition definition) {
        return !dropPublications.get() && !pauseConsumers.get();
    }

    @Override
    public void cleanup(ExperimentDefinition definition) {
        log.info("[RESILIENCE] Cleaning up Kafka interruption experiment '{}'", definition.getId());
        dropPublications.set(false);
        pauseConsumers.set(false);
        injected.set(false);
    }

    public boolean isDropPublications() {
        return dropPublications.get();
    }

    public boolean isPauseConsumers() {
        return pauseConsumers.get();
    }
}
