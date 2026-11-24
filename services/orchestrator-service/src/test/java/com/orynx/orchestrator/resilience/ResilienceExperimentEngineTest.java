package com.orynx.orchestrator.resilience;

import com.orynx.orchestrator.coordination.lock.DistributedLockService;
import com.orynx.orchestrator.resilience.adapter.FailureInjectionAdapter;
import com.orynx.orchestrator.resilience.adapter.RedisInterruptionAdapter;
import com.orynx.orchestrator.resilience.engine.ExperimentRegistry;
import com.orynx.orchestrator.resilience.engine.ResilienceExperimentEngine;
import com.orynx.orchestrator.resilience.metrics.ResilienceMetrics;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ExperimentResult;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.ExperimentSafetyValidator;
import com.orynx.orchestrator.resilience.safety.SafetyValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilienceExperimentEngineTest {

    private ExperimentSafetyValidator validator;
    private ResilienceMetrics metrics;
    private ExperimentRegistry registry;
    private SimpleMeterRegistry meterRegistry;
    private RedisInterruptionAdapter redisAdapter;
    private ResilienceExperimentEngine engine;

    @BeforeEach
    void setUp() {
        validator = new ExperimentSafetyValidator();
        meterRegistry = new SimpleMeterRegistry();
        metrics = new ResilienceMetrics(meterRegistry);
        registry = new ExperimentRegistry();
        redisAdapter = new RedisInterruptionAdapter();

        engine = new ResilienceExperimentEngine(
                validator,
                metrics,
                registry,
                List.of(redisAdapter)
        );
    }

    private ExperimentDefinition sampleDefinition(String id) {
        return ExperimentDefinition.builder()
                .id(id)
                .name("Redis Isolation Test")
                .scenarioType(ScenarioType.REDIS_INTERRUPTION)
                .targetComponent("redis")
                .targetResource("isolated-redis-shard-1")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(2000)
                .expectedBehavior("Rejects workflows gracefully")
                .recoveryStrategy("Reconnect")
                .build();
    }

    @Test
    @DisplayName("Successful experiment run executes all phases and records PASS")
    void successfulExperimentRun() {
        ExperimentDefinition def = sampleDefinition("exp-success-001");
        ExperimentResult result = engine.runExperiment(def);

        assertNotNull(result);
        assertEquals(ExperimentResult.Outcome.PASS, result.getOutcome());
        assertTrue(result.isCleanupSuccessful());
        assertTrue(result.getVerificationErrors().isEmpty());
        assertTrue(result.getObservedBehaviors().size() >= 4);

        // Verify registry recorded
        assertTrue(registry.getResult("exp-success-001").isPresent());
        assertTrue(registry.getDefinition("exp-success-001").isPresent());

        // Verify metrics
        assertEquals(0, metrics.getActiveExperimentsCount());
        double completed = meterRegistry.get("orynx.resilience.experiments.total")
                .tag("scenario", "redis_interruption")
                .tag("outcome", "pass")
                .counter().count();
        assertEquals(1.0, completed);
    }

    @Test
    @DisplayName("Concurrent experiment execution is blocked by concurrency guard")
    void concurrentExecutionBlocked() throws Exception {
        ExperimentDefinition def1 = sampleDefinition("exp-slow-001").toBuilder().durationMs(500).build();
        ExperimentDefinition def2 = sampleDefinition("exp-fast-002").toBuilder().durationMs(100).build();

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean secondAttemptFailed = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            started.countDown();
            engine.runExperiment(def1);
        });

        t1.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50); // Ensure t1 is in running state

        try {
            engine.runExperiment(def2);
        } catch (SafetyValidationException sve) {
            secondAttemptFailed.set(true);
            assertTrue(sve.getMessage().contains("already running"));
        }

        t1.join(3000);
        assertTrue(secondAttemptFailed.get(), "Second concurrent experiment must be blocked");
    }

    @Test
    @DisplayName("Distributed lock acquisition failure blocks experiment execution")
    void distributedLockBlocksExecution() {
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryAcquire(eq(ResilienceExperimentEngine.RESILIENCE_LOCK_KEY), any(), any()))
                .thenReturn(false);

        ResilienceExperimentEngine lockGuardedEngine = new ResilienceExperimentEngine(
                validator,
                metrics,
                registry,
                List.of(redisAdapter),
                lockService,
                null
        );

        ExperimentDefinition def = sampleDefinition("exp-locked-001");
        SafetyValidationException ex = assertThrows(SafetyValidationException.class, () ->
                lockGuardedEngine.runExperiment(def)
        );
        assertTrue(ex.getMessage().contains("Cluster resilience lock is already held"));
    }

    @Test
    @DisplayName("Stale lock owner is prevented from injecting fault after ownership loss (M-02)")
    void staleLockOwnerBlockedBeforeInjection() {
        DistributedLockService lockService = mock(DistributedLockService.class);
        // Initial lock acquisition succeeds
        when(lockService.tryAcquire(eq(ResilienceExperimentEngine.RESILIENCE_LOCK_KEY), any(), any()))
                .thenReturn(true);
        // But lease expires / ownership lost before injection step
        when(lockService.isLockHeldBy(eq(ResilienceExperimentEngine.RESILIENCE_LOCK_KEY), any()))
                .thenReturn(false);

        FailureInjectionAdapter adapter = mock(FailureInjectionAdapter.class);
        when(adapter.getSupportedScenario()).thenReturn(ScenarioType.REDIS_INTERRUPTION);

        ResilienceExperimentEngine staleOwnerEngine = new ResilienceExperimentEngine(
                validator,
                metrics,
                registry,
                List.of(adapter),
                lockService,
                null
        );

        ExperimentDefinition def = sampleDefinition("exp-stale-001");
        ExperimentResult result = staleOwnerEngine.runExperiment(def);

        assertEquals(ExperimentResult.Outcome.FAIL, result.getOutcome());
        assertTrue(result.getVerificationErrors().stream().anyMatch(e -> e.contains("Stale lock owner detected")));
        // Verify adapter was NEVER injected because stale owner was caught
        verify(adapter, never()).inject(any());
        // Verify lock release was not attempted since ownership was lost
        verify(lockService, never()).release(any(), any());
    }

    @Test
    @DisplayName("Stale lock owner loss during observation skips cleanup to protect active lock owner (M-02)")
    void staleLockOwnerLossDuringExecutionSkipsCleanup() {
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryAcquire(eq(ResilienceExperimentEngine.RESILIENCE_LOCK_KEY), any(), any()))
                .thenReturn(true);
        // Lock held at start and injection, but lost during observation
        when(lockService.isLockHeldBy(eq(ResilienceExperimentEngine.RESILIENCE_LOCK_KEY), any()))
                .thenReturn(true)  // check before injection: true
                .thenReturn(false); // check before recovery & cleanup: false

        FailureInjectionAdapter adapter = mock(FailureInjectionAdapter.class);
        when(adapter.getSupportedScenario()).thenReturn(ScenarioType.REDIS_INTERRUPTION);
        when(adapter.verifyInjected(any())).thenReturn(true);

        ResilienceExperimentEngine staleOwnerEngine = new ResilienceExperimentEngine(
                validator,
                metrics,
                registry,
                List.of(adapter),
                lockService,
                null
        );

        ExperimentDefinition def = sampleDefinition("exp-stale-during-001").toBuilder().durationMs(50).build();
        ExperimentResult result = staleOwnerEngine.runExperiment(def);

        assertEquals(ExperimentResult.Outcome.FAIL, result.getOutcome());
        assertTrue(result.getVerificationErrors().stream().anyMatch(e -> e.contains("Stale lock owner detected") || e.contains("skipped cleanup")));
        // Verify adapter cleanup was SKIPPED to protect the new owner
        verify(adapter, never()).cleanup(any());
        verify(lockService, never()).release(any(), any());
    }

    @Test
    @DisplayName("Quarantine verification fails closed on Redis connectivity error (M-04)")
    void quarantineFailsClosedOnRedisError() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(eq(ResilienceExperimentEngine.QUARANTINE_KEY)))
                .thenThrow(new RedisConnectionFailureException("Simulated Redis connection failure"));

        ResilienceExperimentEngine failClosedEngine = new ResilienceExperimentEngine(
                validator,
                metrics,
                registry,
                List.of(redisAdapter),
                null,
                redisTemplate
        );

        // isQuarantined must return true (fail closed)
        assertTrue(failClosedEngine.isQuarantined());

        // runExperiment must fail with SafetyValidationException
        ExperimentDefinition def = sampleDefinition("exp-quarantine-fail-closed");
        SafetyValidationException ex = assertThrows(SafetyValidationException.class, () ->
                failClosedEngine.runExperiment(def)
        );
        assertTrue(ex.getMessage().contains("Failing closed to preserve cluster safety"));
        assertTrue(ex.getMessage().contains("connectivity error"));
    }

    @Test
    @DisplayName("Timeout watchdog enforces timeoutMs and records TIMED_OUT outcome")
    void timeoutWatchdogEnforcesTimeout() {
        FailureInjectionAdapter hangingAdapter = mock(FailureInjectionAdapter.class);
        when(hangingAdapter.getSupportedScenario()).thenReturn(ScenarioType.REDIS_INTERRUPTION);
        when(hangingAdapter.verifyInjected(any())).thenReturn(true);
        // Simulate hanging inject
        doAnswer(invocation -> {
            Thread.sleep(10000);
            return null;
        }).when(hangingAdapter).inject(any());

        ResilienceExperimentEngine hangingEngine = new ResilienceExperimentEngine(
                validator,
                metrics,
                registry,
                List.of(hangingAdapter)
        );

        ExperimentDefinition def = sampleDefinition("exp-timeout-001")
                .toBuilder()
                .durationMs(100)
                .timeoutMs(250) // Small timeout for test
                .build();

        long start = System.currentTimeMillis();
        ExperimentResult result = hangingEngine.runExperiment(def);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(ExperimentResult.Outcome.TIMED_OUT, result.getOutcome());
        assertTrue(elapsed < 2000, "Should be aborted quickly around timeout threshold, took: " + elapsed + "ms");
        assertTrue(result.isCleanupSuccessful());
        assertTrue(result.getVerificationErrors().stream().anyMatch(e -> e.contains("timeout")));
    }

    @Test
    @DisplayName("Abort request halts experiment and records ABORTED outcome")
    void abortRequestHaltsExperiment() throws Exception {
        ExperimentDefinition def = sampleDefinition("exp-abort-001").toBuilder().durationMs(2000).timeoutMs(5000).build();

        Thread t = new Thread(() -> engine.runExperiment(def));
        t.start();

        Thread.sleep(100);
        boolean aborted = engine.abort("exp-abort-001");
        assertTrue(aborted);

        t.join(3000);

        ExperimentResult result = registry.getResult("exp-abort-001").orElseThrow();
        assertEquals(ExperimentResult.Outcome.ABORTED, result.getOutcome());
        assertTrue(result.isCleanupSuccessful());
    }

    @Test
    @DisplayName("Cleanup failure transitions to CLEANUP_FAILED, activates quarantine, and blocks subsequent runs")
    void cleanupFailureEnforcesQuarantine() {
        FailureInjectionAdapter faultyAdapter = mock(FailureInjectionAdapter.class);
        when(faultyAdapter.getSupportedScenario()).thenReturn(ScenarioType.REDIS_INTERRUPTION);
        when(faultyAdapter.verifyInjected(any())).thenReturn(true);
        when(faultyAdapter.verifyRecovered(any())).thenReturn(true);
        doThrow(new RuntimeException("Simulated disk error during cleanup")).when(faultyAdapter).cleanup(any());

        ResilienceExperimentEngine faultyEngine = new ResilienceExperimentEngine(
                validator,
                metrics,
                registry,
                List.of(faultyAdapter)
        );

        ExperimentDefinition def = sampleDefinition("exp-cleanup-fail-001").toBuilder().durationMs(100).build();
        ExperimentResult result = faultyEngine.runExperiment(def);

        assertEquals(ExperimentResult.Outcome.CLEANUP_FAILED, result.getOutcome());
        assertFalse(result.isCleanupSuccessful());
        assertTrue(result.getVerificationErrors().stream().anyMatch(e -> e.contains("Cleanup failed")));
        assertTrue(faultyEngine.isQuarantined());

        // Subsequent experiment must be rejected due to quarantine
        ExperimentDefinition nextDef = sampleDefinition("exp-subsequent-002");
        SafetyValidationException quarantineEx = assertThrows(SafetyValidationException.class, () ->
                faultyEngine.runExperiment(nextDef)
        );
        assertTrue(quarantineEx.getMessage().contains("QUARANTINE mode"));

        // Clear quarantine via operator reset
        boolean cleared = faultyEngine.clearQuarantine("Manual operator recovery verification");
        assertTrue(cleared);
        assertFalse(faultyEngine.isQuarantined());
    }

    @Test
    @DisplayName("Scheduler lifecycle shutdown executes without error (M-03)")
    void schedulerShutdownExecutesCleanly() {
        engine.shutdown();
        // Subsequent shutdown should be idempotent
        engine.shutdown();
    }
}
