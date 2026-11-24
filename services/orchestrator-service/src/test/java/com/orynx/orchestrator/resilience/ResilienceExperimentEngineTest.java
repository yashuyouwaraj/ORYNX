package com.orynx.orchestrator.resilience;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
    @DisplayName("Abort request halts experiment and records ABORTED outcome")
    void abortRequestHaltsExperiment() throws Exception {
        ExperimentDefinition def = sampleDefinition("exp-abort-001").toBuilder().durationMs(1000).build();

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
    @DisplayName("Cleanup failure transitions to CLEANUP_FAILED outcome")
    void cleanupFailureHandled() {
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

        double cleanupFailures = meterRegistry.get("orynx.resilience.cleanup.failures.total")
                .tag("scenario", "redis_interruption")
                .counter().count();
        assertEquals(1.0, cleanupFailures);
    }
}
