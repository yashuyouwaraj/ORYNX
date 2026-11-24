package com.orynx.orchestrator.resilience.engine;

import com.orynx.orchestrator.resilience.adapter.FailureInjectionAdapter;
import com.orynx.orchestrator.resilience.metrics.ResilienceMetrics;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ExperimentResult;
import com.orynx.orchestrator.resilience.model.ExperimentState;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.ExperimentSafetyValidator;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core execution engine for resilience failure experiments.
 * Orchestrates safety validation, state transitions, fault injection, observation,
 * recovery verification, and guaranteed cleanup with Micrometer observability.
 */
@Slf4j
@Component
public class ResilienceExperimentEngine {

    private final ExperimentSafetyValidator safetyValidator;
    private final ResilienceMetrics resilienceMetrics;
    private final ExperimentRegistry experimentRegistry;
    private final Map<ScenarioType, FailureInjectionAdapter> adapters = new ConcurrentHashMap<>();

    // Concurrency guard: only 1 experiment may run concurrently
    private final AtomicReference<String> activeExperimentId = new AtomicReference<>(null);
    private final AtomicReference<ExperimentState> currentState = new AtomicReference<>(ExperimentState.CREATED);
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);

    public ResilienceExperimentEngine(ExperimentSafetyValidator safetyValidator,
                                      ResilienceMetrics resilienceMetrics,
                                      ExperimentRegistry experimentRegistry,
                                      List<FailureInjectionAdapter> adapterList) {
        this.safetyValidator = safetyValidator;
        this.resilienceMetrics = resilienceMetrics;
        this.experimentRegistry = experimentRegistry;

        if (adapterList != null) {
            for (FailureInjectionAdapter adapter : adapterList) {
                this.adapters.put(adapter.getSupportedScenario(), adapter);
            }
        }
    }

    public ExperimentResult runExperiment(ExperimentDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Experiment definition cannot be null");
        }

        // Concurrency guard
        if (!activeExperimentId.compareAndSet(null, definition.getId())) {
            String active = activeExperimentId.get();
            throw new SafetyValidationException("Another resilience experiment is already running: " + active);
        }

        Instant startTime = Instant.now();
        List<String> observedBehaviors = new ArrayList<>();
        List<String> verificationErrors = new ArrayList<>();
        boolean cleanupSuccessful = false;
        long failureDurationMs = 0;
        long recoveryDurationMs = 0;
        ExperimentResult.Outcome outcome = ExperimentResult.Outcome.FAIL;

        currentState.set(ExperimentState.CREATED);
        transitionState(ExperimentState.CREATED, ExperimentState.VALIDATING);
        resilienceMetrics.experimentStarted();
        experimentRegistry.register(definition);
        abortRequested.set(false);

        FailureInjectionAdapter adapter = null;

        try {
            // Step 1: Validate safety constraints
            safetyValidator.validate(definition);
            adapter = adapters.get(definition.getScenarioType());
            if (adapter == null) {
                throw new SafetyValidationException("No adapter registered for scenario: " + definition.getScenarioType());
            }
            adapter.validate(definition);
            transitionState(ExperimentState.VALIDATING, ExperimentState.READY);
            observedBehaviors.add("Safety validation passed for target: " + definition.getTargetResource());

            checkAborted();

            // Step 2: Inject failure
            transitionState(ExperimentState.READY, ExperimentState.RUNNING);
            long injectionStart = System.currentTimeMillis();
            adapter.inject(definition);
            resilienceMetrics.recordInjection(definition.getScenarioType(), true);
            observedBehaviors.add("Failure injected: " + definition.getScenarioType());

            // Step 3: Verify injection observable
            boolean injectedObservable = adapter.verifyInjected(definition);
            if (!injectedObservable) {
                verificationErrors.add("Failure injection was not observable on target: " + definition.getTargetResource());
                throw new IllegalStateException("Failure injection failed verification");
            }
            observedBehaviors.add("Failure injection verified observable");

            checkAborted();

            // Step 4: Observation window
            transitionState(ExperimentState.RUNNING, ExperimentState.OBSERVING);
            long duration = definition.getDurationMs();
            if (duration > 0) {
                long sleepInterval = Math.min(duration, 500);
                long elapsed = 0;
                while (elapsed < duration) {
                    checkAborted();
                    long step = Math.min(sleepInterval, duration - elapsed);
                    Thread.sleep(step);
                    elapsed += step;
                }
            }
            failureDurationMs = System.currentTimeMillis() - injectionStart;
            observedBehaviors.add("Observation completed for duration: " + failureDurationMs + "ms");

            checkAborted();

            // Step 5: Recovery
            transitionState(ExperimentState.OBSERVING, ExperimentState.RECOVERING);
            long recoveryStart = System.currentTimeMillis();
            adapter.recover(definition);
            observedBehaviors.add("Recovery action triggered");

            // Step 6: Verify recovery
            boolean recovered = adapter.verifyRecovered(definition);
            recoveryDurationMs = System.currentTimeMillis() - recoveryStart;
            resilienceMetrics.recordRecovery(definition.getScenarioType(), recoveryDurationMs);

            if (!recovered) {
                verificationErrors.add("Target component did not recover within expected threshold");
                throw new IllegalStateException("Recovery verification failed");
            }
            observedBehaviors.add("Recovery verified successfully in " + recoveryDurationMs + "ms");

            // Mark completed
            transitionState(ExperimentState.RECOVERING, ExperimentState.COMPLETED);
            outcome = ExperimentResult.Outcome.PASS;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            outcome = ExperimentResult.Outcome.ABORTED;
            verificationErrors.add("Experiment interrupted: " + ie.getMessage());
            forceState(ExperimentState.ABORTED);
        } catch (SafetyValidationException sve) {
            outcome = ExperimentResult.Outcome.FAIL;
            verificationErrors.add("Safety validation failed: " + sve.getMessage());
            forceState(ExperimentState.FAILED);
        } catch (Exception ex) {
            if (abortRequested.get()) {
                outcome = ExperimentResult.Outcome.ABORTED;
                verificationErrors.add("Experiment was aborted by operator");
                forceState(ExperimentState.ABORTED);
            } else {
                outcome = ExperimentResult.Outcome.FAIL;
                verificationErrors.add("Experiment failed: " + ex.getMessage());
                forceState(ExperimentState.FAILED);
            }
        } finally {
            // Mandatory cleanup in finally block
            if (adapter != null) {
                try {
                    adapter.cleanup(definition);
                    cleanupSuccessful = true;
                    observedBehaviors.add("Cleanup completed successfully");
                } catch (Exception cleanupEx) {
                    log.error("[RESILIENCE] Cleanup failed for experiment {}: {}", definition.getId(), cleanupEx.getMessage(), cleanupEx);
                    cleanupSuccessful = false;
                    verificationErrors.add("Cleanup failed: " + cleanupEx.getMessage());
                    forceState(ExperimentState.CLEANUP_FAILED);
                    outcome = ExperimentResult.Outcome.CLEANUP_FAILED;
                    resilienceMetrics.recordCleanupFailure(definition.getScenarioType());
                }
            } else {
                cleanupSuccessful = true;
            }

            resilienceMetrics.recordExperimentCompleted(definition.getScenarioType(), outcome);
            resilienceMetrics.experimentEnded();
            activeExperimentId.set(null);
        }

        Instant endTime = Instant.now();

        Map<String, Object> metricsSnapshot = new HashMap<>();
        metricsSnapshot.put("scenario", definition.getScenarioType().name());
        metricsSnapshot.put("failureDurationMs", failureDurationMs);
        metricsSnapshot.put("recoveryDurationMs", recoveryDurationMs);
        metricsSnapshot.put("cleanupSuccessful", cleanupSuccessful);

        ExperimentResult result = ExperimentResult.builder()
                .experimentId(definition.getId())
                .outcome(outcome)
                .message(outcome == ExperimentResult.Outcome.PASS ? "Experiment passed all verification assertions" : "Experiment finished with errors")
                .startTime(startTime)
                .endTime(endTime)
                .failureDurationMs(failureDurationMs)
                .recoveryDurationMs(recoveryDurationMs)
                .observedBehaviors(observedBehaviors)
                .verificationErrors(verificationErrors)
                .cleanupSuccessful(cleanupSuccessful)
                .metricsSnapshot(metricsSnapshot)
                .build();

        experimentRegistry.recordResult(result);
        log.info("[RESILIENCE] Experiment '{}' finished with outcome: {} (cleanup={})",
                definition.getId(), outcome, cleanupSuccessful);

        return result;
    }

    public boolean abort(String experimentId) {
        String active = activeExperimentId.get();
        if (active != null && active.equals(experimentId)) {
            log.warn("[RESILIENCE] Operator requested abort for active experiment: {}", experimentId);
            abortRequested.set(true);
            return true;
        }
        return false;
    }

    public ExperimentState getCurrentState() {
        return currentState.get();
    }

    public String getActiveExperimentId() {
        return activeExperimentId.get();
    }

    private void checkAborted() {
        if (abortRequested.get()) {
            throw new IllegalStateException("Experiment execution aborted by operator");
        }
    }

    private void transitionState(ExperimentState expectedCurrent, ExperimentState target) {
        if (!currentState.get().canTransitionTo(target)) {
            throw new IllegalStateException("Invalid state transition from " + currentState.get() + " to " + target);
        }
        currentState.set(target);
    }

    private void forceState(ExperimentState target) {
        currentState.set(target);
    }
}
