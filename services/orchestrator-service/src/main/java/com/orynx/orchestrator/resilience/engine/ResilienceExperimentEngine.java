package com.orynx.orchestrator.resilience.engine;

import com.orynx.orchestrator.coordination.lock.DistributedLockService;
import com.orynx.orchestrator.resilience.adapter.FailureInjectionAdapter;
import com.orynx.orchestrator.resilience.metrics.ResilienceMetrics;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ExperimentResult;
import com.orynx.orchestrator.resilience.model.ExperimentState;
import com.orynx.orchestrator.resilience.model.InjectionLevel;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.ExperimentSafetyValidator;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import com.orynx.orchestrator.resilience.safety.StaleLockOwnerException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core execution engine for resilience failure experiments.
 * Orchestrates safety validation, state transitions, fault injection, observation,
 * recovery verification, and guaranteed cleanup with Micrometer observability.
 *
 * Remediated in Step 25.2 & Step 25.4:
 * - Distributed locking via DistributedLockService with stale-owner verification
 * - Fail-closed quarantine validation on Redis connectivity failures
 * - Scheduled timeout watchdog actively enforcing experiment timeoutMs with thread interruption
 * - Active thread interruption on operator abort
 * - Quarantine mode enforcement on cleanup failure with clearQuarantine operator reset
 * - Clean lifecycle shutdown of timeout scheduler via @PreDestroy
 * - Truthful injection level classification in results
 */
@Slf4j
@Component
public class ResilienceExperimentEngine {

    public static final String RESILIENCE_LOCK_KEY = "resilience-experiment-leader";
    public static final String QUARANTINE_KEY = "orynx:{admission}:resilience_quarantine";

    private final ExperimentSafetyValidator safetyValidator;
    private final ResilienceMetrics resilienceMetrics;
    private final ExperimentRegistry experimentRegistry;
    private final Map<ScenarioType, FailureInjectionAdapter> adapters = new ConcurrentHashMap<>();

    private final DistributedLockService distributedLockService;
    private final StringRedisTemplate redisTemplate;

    // Concurrency guard: only 1 experiment may run concurrently in this node
    private final AtomicReference<String> activeExperimentId = new AtomicReference<>(null);
    private final AtomicReference<ExperimentState> currentState = new AtomicReference<>(ExperimentState.CREATED);
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);
    private final AtomicReference<Thread> executingThread = new AtomicReference<>(null);

    // Quarantine guard: if cleanup fails, engine enters quarantine until operator reset
    private final AtomicBoolean quarantined = new AtomicBoolean(false);
    private final AtomicLong quarantineGeneration = new AtomicLong(0);

    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "resilience-timeout-watchdog");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public ResilienceExperimentEngine(ExperimentSafetyValidator safetyValidator,
                                      ResilienceMetrics resilienceMetrics,
                                      ExperimentRegistry experimentRegistry,
                                      List<FailureInjectionAdapter> adapterList,
                                      @Autowired(required = false) DistributedLockService distributedLockService,
                                      @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.safetyValidator = safetyValidator;
        this.resilienceMetrics = resilienceMetrics;
        this.experimentRegistry = experimentRegistry;
        this.distributedLockService = distributedLockService;
        this.redisTemplate = redisTemplate;

        if (adapterList != null) {
            for (FailureInjectionAdapter adapter : adapterList) {
                this.adapters.put(adapter.getSupportedScenario(), adapter);
            }
        }
    }

    public ResilienceExperimentEngine(ExperimentSafetyValidator safetyValidator,
                                      ResilienceMetrics resilienceMetrics,
                                      ExperimentRegistry experimentRegistry,
                                      List<FailureInjectionAdapter> adapterList) {
        this(safetyValidator, resilienceMetrics, experimentRegistry, adapterList, null, null);
    }

    public ExperimentResult runExperiment(ExperimentDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Experiment definition cannot be null");
        }

        // Quarantine check (fails closed on Redis connectivity error)
        verifyQuarantineSafety();

        // JVM concurrency guard
        if (!activeExperimentId.compareAndSet(null, definition.getId())) {
            String active = activeExperimentId.get();
            throw new SafetyValidationException("Another resilience experiment is already running: " + active);
        }

        // Distributed cluster lock guard (F-03)
        String lockToken = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        long timeoutMs = definition.getTimeoutMs() > 0 ? definition.getTimeoutMs() : 60000;
        Duration lockDuration = Duration.ofMillis(timeoutMs + 30000);

        if (distributedLockService != null) {
            lockAcquired = distributedLockService.tryAcquire(RESILIENCE_LOCK_KEY, lockToken, lockDuration);
            if (!lockAcquired) {
                activeExperimentId.set(null);
                throw new SafetyValidationException("Cluster resilience lock is already held by another node: " + RESILIENCE_LOCK_KEY);
            }
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

        Thread currentThread = Thread.currentThread();
        executingThread.set(currentThread);
        AtomicBoolean timedOut = new AtomicBoolean(false);

        // Schedule timeout watchdog (F-04)
        ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
            timedOut.set(true);
            log.error("[RESILIENCE] Experiment '{}' timed out after {}ms. Interrupting execution thread...",
                    definition.getId(), timeoutMs);
            currentThread.interrupt();
        }, timeoutMs, TimeUnit.MILLISECONDS);

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

            checkAborted(timedOut);

            // Stale-owner check before injection (M-02)
            verifyLockOwnership(lockAcquired, lockToken);

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

            checkAborted(timedOut);

            // Step 4: Observation window
            transitionState(ExperimentState.RUNNING, ExperimentState.OBSERVING);
            long duration = definition.getDurationMs();
            if (duration > 0) {
                long sleepInterval = Math.min(duration, 500);
                long elapsed = 0;
                while (elapsed < duration) {
                    checkAborted(timedOut);
                    long step = Math.min(sleepInterval, duration - elapsed);
                    Thread.sleep(step);
                    elapsed += step;
                }
            }
            failureDurationMs = System.currentTimeMillis() - injectionStart;
            observedBehaviors.add("Observation completed for duration: " + failureDurationMs + "ms");

            checkAborted(timedOut);

            // Stale-owner check before recovery (M-02)
            verifyLockOwnership(lockAcquired, lockToken);

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
            if (timedOut.get()) {
                outcome = ExperimentResult.Outcome.TIMED_OUT;
                verificationErrors.add("Experiment exceeded timeout threshold of " + timeoutMs + "ms");
                forceState(ExperimentState.TIMED_OUT);
            } else {
                outcome = ExperimentResult.Outcome.ABORTED;
                verificationErrors.add("Experiment interrupted: " + ie.getMessage());
                forceState(ExperimentState.ABORTED);
            }
        } catch (StaleLockOwnerException sloe) {
            outcome = ExperimentResult.Outcome.FAIL;
            verificationErrors.add("Stale lock owner detected: " + sloe.getMessage());
            forceState(ExperimentState.FAILED);
        } catch (SafetyValidationException sve) {
            outcome = ExperimentResult.Outcome.FAIL;
            verificationErrors.add("Safety validation failed: " + sve.getMessage());
            forceState(ExperimentState.FAILED);
        } catch (Exception ex) {
            if (timedOut.get()) {
                outcome = ExperimentResult.Outcome.TIMED_OUT;
                verificationErrors.add("Experiment timed out after " + timeoutMs + "ms: " + ex.getMessage());
                forceState(ExperimentState.TIMED_OUT);
            } else if (abortRequested.get()) {
                outcome = ExperimentResult.Outcome.ABORTED;
                verificationErrors.add("Experiment was aborted by operator");
                forceState(ExperimentState.ABORTED);
            } else {
                outcome = ExperimentResult.Outcome.FAIL;
                verificationErrors.add("Experiment failed: " + ex.getMessage());
                forceState(ExperimentState.FAILED);
            }
        } finally {
            timeoutFuture.cancel(true);
            executingThread.set(null);

            // Step A: UNCONDITIONALLY neutralize local in-process fault state for this experiment (C-01)
            boolean localNeutralized = false;
            if (adapter != null) {
                try {
                    adapter.neutralizeLocalFault(definition);
                    localNeutralized = true;
                    observedBehaviors.add("Local fault neutralized");
                } catch (Exception localEx) {
                    log.error("[RESILIENCE] Failed to neutralize local fault for experiment {}: {}",
                            definition.getId(), localEx.getMessage(), localEx);
                    verificationErrors.add("Local fault neutralization failed: " + localEx.getMessage());
                    localNeutralized = false;
                }
            } else {
                localNeutralized = true;
            }

            // Step B: Verify distributed lock ownership for shared-resource cleanup (M-01, M-02)
            boolean isStaleOwner = false;
            if (lockAcquired && distributedLockService != null) {
                try {
                    isStaleOwner = !distributedLockService.isLockHeldBy(RESILIENCE_LOCK_KEY, lockToken);
                } catch (Exception e) {
                    log.error("[RESILIENCE] Failed to verify lock ownership during cleanup: {}. Treating as unverified/stale.", e.getMessage());
                    isStaleOwner = true;
                }
            }

            boolean sharedCleanupSuccessful = false;
            boolean sharedCleanupFailed = false;
            if (isStaleOwner) {
                log.error("[RESILIENCE] Stale lock owner detected during cleanup. Lock is no longer held by token {}. Aborting shared-resource cleanup to protect active cluster owner.", lockToken);
                verificationErrors.add("Stale owner detected: skipped shared cleanup to avoid interfering with new lock holder");
                sharedCleanupSuccessful = false;
            } else if (adapter != null) {
                try {
                    adapter.cleanup(definition);
                    sharedCleanupSuccessful = true;
                    observedBehaviors.add("Cleanup completed successfully");
                } catch (Exception cleanupEx) {
                    log.error("[RESILIENCE] Cleanup failed for experiment {}: {}", definition.getId(), cleanupEx.getMessage(), cleanupEx);
                    sharedCleanupSuccessful = false;
                    sharedCleanupFailed = true;
                    verificationErrors.add("Cleanup failed: " + cleanupEx.getMessage());
                }
            } else {
                sharedCleanupSuccessful = true;
            }

            cleanupSuccessful = localNeutralized && sharedCleanupSuccessful && !isStaleOwner;

            // Step C: Deterministic cleanup failure, quarantine, and outcome reconciliation (Step 25.8 M-01 & m-01)
            // Precedence:
            //   1. Any cleanup failure (local neutralization failure OR shared cleanup failure) unconditionally
            //      forces CLEANUP_FAILED and activates quarantine — even if lock ownership was also lost.
            //   2. Stale lock ownership (with successful local neutralization) forbids PASS by downgrading
            //      PASS -> FAIL/FAILED while preserving existing terminal causes (TIMED_OUT, ABORTED, FAILED).
            boolean cleanupFailureOccurred = !localNeutralized || sharedCleanupFailed;
            if (cleanupFailureOccurred) {
                String quarantineReason = !localNeutralized
                        ? "Quarantined due to local fault neutralization failure in experiment " + definition.getId()
                        : "Quarantined due to cleanup failure in experiment " + definition.getId();
                activateQuarantine(quarantineReason);
                forceState(ExperimentState.CLEANUP_FAILED);
                outcome = ExperimentResult.Outcome.CLEANUP_FAILED;
                resilienceMetrics.recordCleanupFailure(definition.getScenarioType());
            } else if (isStaleOwner) {
                outcome = reconcileStaleOwnerOutcome(outcome);
            }

            resilienceMetrics.recordExperimentCompleted(definition.getScenarioType(), outcome);
            resilienceMetrics.experimentEnded();
            activeExperimentId.set(null);

            // Release distributed lock only if cleanup succeeded and we are still rightful owner (F-03, F-07, M-02)
            if (lockAcquired && distributedLockService != null && cleanupSuccessful && !isStaleOwner) {
                try {
                    distributedLockService.release(RESILIENCE_LOCK_KEY, lockToken);
                } catch (Exception e) {
                    log.warn("Failed to release distributed resilience lock", e);
                }
            }
        }

        Instant endTime = Instant.now();

        Map<String, Object> metricsSnapshot = new HashMap<>();
        metricsSnapshot.put("scenario", definition.getScenarioType().name());
        metricsSnapshot.put("failureDurationMs", failureDurationMs);
        metricsSnapshot.put("recoveryDurationMs", recoveryDurationMs);
        metricsSnapshot.put("cleanupSuccessful", cleanupSuccessful);

        InjectionLevel resolvedLevel = adapter != null ? adapter.getInjectionLevel() : definition.getInjectionLevel();

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
                .injectionLevel(resolvedLevel)
                .build();

        experimentRegistry.recordResult(result);
        log.info("[RESILIENCE] Experiment '{}' finished with outcome: {} (cleanup={}, level={})",
                definition.getId(), outcome, cleanupSuccessful, resolvedLevel);

        return result;
    }

    public boolean abort(String experimentId) {
        String active = activeExperimentId.get();
        if (active != null && active.equals(experimentId)) {
            log.warn("[RESILIENCE] Operator requested abort for active experiment: {}", experimentId);
            abortRequested.set(true);
            Thread t = executingThread.get();
            if (t != null) {
                t.interrupt();
            }
            return true;
        }
        return false;
    }

    /**
     * Clears quarantine safely: deletes the Redis quarantine key FIRST, and only clears the local
     * quarantine flag if Redis deletion succeeds AND no concurrent quarantine activation occurred.
     *
     * @param operatorReason non-blank audit reason for clearing quarantine
     * @return true if quarantine was cleared, false if a concurrent quarantine activation superseded this clear
     */
    public boolean clearQuarantine(String operatorReason) {
        if (operatorReason == null || operatorReason.isBlank()) {
            throw new IllegalArgumentException("Operator reason is required to clear quarantine");
        }
        long observedGeneration = quarantineGeneration.get();
        log.warn("[RESILIENCE] Operator clearing quarantine (gen={}). Reason: {}", observedGeneration, operatorReason);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(QUARANTINE_KEY);
            } catch (Exception re) {
                log.error("[RESILIENCE] Failed to delete quarantine in Redis; preserving local quarantine: {}", re.getMessage());
                throw new IllegalStateException("Failed to clear quarantine in Redis due to connectivity error: " + re.getMessage(), re);
            }
        }
        if (quarantineGeneration.get() != observedGeneration) {
            log.warn("[RESILIENCE] Concurrent quarantine activation detected during clearQuarantine (gen {} -> {}); preserving quarantine.",
                    observedGeneration, quarantineGeneration.get());
            return false;
        }
        quarantined.set(false);
        return true;
    }

    private void activateQuarantine(String reason) {
        quarantineGeneration.incrementAndGet();
        quarantined.set(true);
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(QUARANTINE_KEY, reason);
            } catch (Exception re) {
                log.error("[RESILIENCE] Failed to persist quarantine key in Redis (local quarantine remains active): {}", re.getMessage(), re);
            }
        }
    }

    private ExperimentResult.Outcome reconcileStaleOwnerOutcome(ExperimentResult.Outcome currentOutcome) {
        if (currentOutcome == ExperimentResult.Outcome.PASS) {
            forceState(ExperimentState.FAILED);
            return ExperimentResult.Outcome.FAIL;
        }
        // Preserve already meaningful terminal outcomes (TIMED_OUT, ABORTED, FAIL, CLEANUP_FAILED)
        return currentOutcome;
    }

    /**
     * Checks whether the engine is in quarantine mode.
     * Fails closed: if Redis cannot be contacted, treats the engine as quarantined to prevent unsafe executions.
     */
    public boolean isQuarantined() {
        if (quarantined.get()) {
            return true;
        }
        if (redisTemplate != null) {
            try {
                Boolean hasKey = redisTemplate.hasKey(QUARANTINE_KEY);
                return Boolean.TRUE.equals(hasKey);
            } catch (Exception e) {
                log.error("[RESILIENCE] Redis connectivity error during quarantine check: {}. Failing closed.", e.getMessage());
                return true;
            }
        }
        return false;
    }

    /**
     * Authoritative check for quarantine safety before starting an experiment.
     * Fails closed on any Redis connection failure, throwing SafetyValidationException.
     */
    public void verifyQuarantineSafety() {
        if (quarantined.get()) {
            throw new SafetyValidationException("Resilience engine is in QUARANTINE mode due to previous cleanup failure on this node. Operator intervention required via clearQuarantine().");
        }
        if (redisTemplate != null) {
            try {
                Boolean hasKey = redisTemplate.hasKey(QUARANTINE_KEY);
                if (Boolean.TRUE.equals(hasKey)) {
                    throw new SafetyValidationException("Resilience engine is in QUARANTINE mode due to previous cleanup failure in the cluster. Operator intervention required via clearQuarantine().");
                }
            } catch (SafetyValidationException sve) {
                throw sve;
            } catch (Exception e) {
                log.error("[RESILIENCE] Redis connectivity error during quarantine verification: {}", e.getMessage(), e);
                throw new SafetyValidationException("Failed to verify quarantine status in Redis due to connectivity error: " + e.getMessage() + ". Failing closed to preserve cluster safety.");
            }
        }
    }

    public ExperimentState getCurrentState() {
        return currentState.get();
    }

    public String getActiveExperimentId() {
        return activeExperimentId.get();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[RESILIENCE] Shutting down timeout scheduler...");
        try {
            timeoutScheduler.shutdownNow();
            if (!timeoutScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("[RESILIENCE] Timeout scheduler did not terminate cleanly within 2s");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void verifyLockOwnership(boolean lockAcquired, String lockToken) {
        if (!lockAcquired || distributedLockService == null) {
            return;
        }
        boolean held = false;
        try {
            held = distributedLockService.isLockHeldBy(RESILIENCE_LOCK_KEY, lockToken);
        } catch (Exception e) {
            log.error("[RESILIENCE] Lock ownership check failed due to error: {}", e.getMessage());
            held = false;
        }
        if (!held) {
            throw new StaleLockOwnerException("Distributed lock ownership was lost for " + RESILIENCE_LOCK_KEY + " (stale owner detected). Terminating experiment to prevent unauthorized execution.");
        }
    }

    private void checkAborted(AtomicBoolean timedOut) {
        if (timedOut.get() || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Experiment exceeded timeout threshold");
        }
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
