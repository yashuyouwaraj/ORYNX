package com.orynx.orchestrator.resilience;

import com.orynx.orchestrator.coordination.admission.AdmissionDecision;
import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.coordination.admission.RedisWorkflowAdmissionController;
import com.orynx.orchestrator.coordination.admission.WorkflowAdmissionException;
import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Adversarial concurrency, snapshot atomicity, and strict ownership tests for {@link ResilienceFaultRegistry}
 * and {@link RedisWorkflowAdmissionController} (Step 25.8 M-02 & M-03).
 */
class ResilienceFaultRegistryConcurrencyTest {

    @Test
    @DisplayName("M-03: Null and blank experimentIds are strictly rejected on activate and deactivate")
    void strictOwnershipRejectsNullAndBlankIds() {
        ResilienceFaultRegistry registry = new ResilienceFaultRegistry();

        assertThrows(IllegalArgumentException.class, () ->
                registry.activateFault(ScenarioType.REDIS_INTERRUPTION, null, "redis-1", Map.of("policy", "FAIL_CLOSED")));
        assertThrows(IllegalArgumentException.class, () ->
                registry.activateFault(ScenarioType.REDIS_INTERRUPTION, "", "redis-1", Map.of("policy", "FAIL_CLOSED")));
        assertThrows(IllegalArgumentException.class, () ->
                registry.activateFault(ScenarioType.REDIS_INTERRUPTION, "   ", "redis-1", Map.of("policy", "FAIL_CLOSED")));
        assertThrows(IllegalArgumentException.class, () ->
                registry.activateFault(null, "exp-1", "redis-1", Map.of()));

        registry.activateFault(ScenarioType.REDIS_INTERRUPTION, "exp-live", "redis-1", Map.of("policy", "FAIL_CLOSED"));

        assertThrows(IllegalArgumentException.class, () ->
                registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, null));
        assertThrows(IllegalArgumentException.class, () ->
                registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, ""));
        assertThrows(IllegalArgumentException.class, () ->
                registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, "   "));
        assertThrows(IllegalArgumentException.class, () ->
                registry.deactivateFault(null, "exp-live"));

        // Live fault must remain completely intact after rejected invalid calls
        assertTrue(registry.isRedisFaultActive());
        assertEquals("exp-live", registry.getActiveRedisExperimentId());
        assertEquals("exp-live", registry.getFaultOwner(ScenarioType.REDIS_INTERRUPTION));
        assertFalse(registry.isFaultActiveFor(ScenarioType.REDIS_INTERRUPTION, null));
        assertFalse(registry.isFaultActiveFor(ScenarioType.REDIS_INTERRUPTION, "   "));
        assertTrue(registry.isFaultActiveFor(ScenarioType.REDIS_INTERRUPTION, "exp-live"));
    }

    @Test
    @DisplayName("O-01: Conflicting live activation is rejected with IllegalStateException while same-owner re-activation succeeds")
    void conflictingLiveActivationRejectedAndSameOwnerReactivationAllowed() {
        ResilienceFaultRegistry registry = new ResilienceFaultRegistry();

        registry.activateFault(ScenarioType.REDIS_INTERRUPTION, "exp-A", "redis-1", Map.of("policy", "FAIL_CLOSED"));
        assertEquals("exp-A", registry.getActiveRedisExperimentId());
        assertEquals(AdmissionFailurePolicy.FAIL_CLOSED, registry.getActiveRedisFault().orElseThrow().getAdmissionFailurePolicy());

        // Different live experiment B must NOT silently overwrite active owner A
        IllegalStateException conflict = assertThrows(IllegalStateException.class, () ->
                registry.activateFault(ScenarioType.REDIS_INTERRUPTION, "exp-B", "redis-2", Map.of("policy", "FAIL_OPEN")));
        assertTrue(conflict.getMessage().contains("exp-A"));
        assertTrue(conflict.getMessage().contains("exp-B"));

        // Owner A remains intact
        assertEquals("exp-A", registry.getActiveRedisExperimentId());
        assertEquals(AdmissionFailurePolicy.FAIL_CLOSED, registry.getActiveRedisFault().orElseThrow().getAdmissionFailurePolicy());

        // Same-owner A re-activation is explicitly allowed and updates atomically
        registry.activateFault(ScenarioType.REDIS_INTERRUPTION, "exp-A", "redis-1", Map.of("policy", "FAIL_OPEN"));
        assertEquals("exp-A", registry.getActiveRedisExperimentId());
        assertEquals(AdmissionFailurePolicy.FAIL_OPEN, registry.getActiveRedisFault().orElseThrow().getAdmissionFailurePolicy());

        // After owner A deactivates, owner B can activate cleanly
        assertTrue(registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, "exp-A"));
        registry.activateFault(ScenarioType.REDIS_INTERRUPTION, "exp-B", "redis-2", Map.of("policy", "FAIL_CLOSED"));
        assertEquals("exp-B", registry.getActiveRedisExperimentId());
    }

    @Test
    @DisplayName("M-02 & O-01: Concurrent stale deactivation (exp-A) vs newer activation (exp-B) never diverges or removes exp-B")
    void concurrentStaleDeactivationVsNewerActivationMaintainsConsistency() throws Exception {
        ResilienceFaultRegistry registry = new ResilienceFaultRegistry();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int iterations = 400;

        try {
            for (int i = 0; i < iterations; i++) {
                String staleId = "exp-A-" + i;
                String newerId = "exp-B-" + i;
                registry.activateFault(ScenarioType.REDIS_INTERRUPTION, staleId, "redis-1", Map.of("policy", "FAIL_CLOSED"));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Boolean> staleDeactivate = executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    return registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, staleId);
                });
                Future<Boolean> newerActivate = executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    try {
                        registry.activateFault(ScenarioType.REDIS_INTERRUPTION, newerId, "redis-2", Map.of("policy", "FAIL_OPEN"));
                        return true;
                    } catch (IllegalStateException conflict) {
                        // Expected if newerActivate ran before staleDeactivate removed exp-A (O-01)
                        return false;
                    }
                });

                boolean deactivatedA = staleDeactivate.get(3, TimeUnit.SECONDS);
                boolean activatedB = newerActivate.get(3, TimeUnit.SECONDS);

                // exp-A MUST have been deactivated cleanly
                assertTrue(deactivatedA, "exp-A must be deactivated at iteration " + i);

                if (!activatedB) {
                    // exp-B was cleanly rejected while exp-A was still live; now that exp-A is gone, exp-B activates
                    assertFalse(registry.isRedisFaultActive());
                    registry.activateFault(ScenarioType.REDIS_INTERRUPTION, newerId, "redis-2", Map.of("policy", "FAIL_OPEN"));
                }

                // A late stale deactivation from exp-A must NEVER remove exp-B
                assertFalse(registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, staleId));

                // Invariant: exp-B MUST be active and ALL views must agree atomically
                Optional<ResilienceFaultRegistry.ActiveFault> snap = registry.getActiveRedisFault();
                assertTrue(snap.isPresent(), "Newer fault exp-B must remain active at iteration " + i);
                assertEquals(newerId, snap.get().getExperimentId());
                assertEquals(AdmissionFailurePolicy.FAIL_OPEN, snap.get().getAdmissionFailurePolicy());
                assertTrue(registry.isFaultActive(ScenarioType.REDIS_INTERRUPTION));
                assertTrue(registry.isRedisFaultActive());
                assertEquals(newerId, registry.getFaultOwner(ScenarioType.REDIS_INTERRUPTION));
                assertEquals(newerId, registry.getActiveRedisExperimentId());

                // Clean up exp-B
                assertTrue(registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, newerId));
                assertFalse(registry.isRedisFaultActive());
                assertNull(registry.getFaultOwner(ScenarioType.REDIS_INTERRUPTION));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("M-02: Concurrent tryAdmit() readers never observe torn policy state during FAIL_OPEN activation/deactivation")
    @SuppressWarnings("unchecked")
    void concurrentAdmissionReadsNeverObserveTornFaultPolicy() throws Exception {
        ResilienceFaultRegistry registry = new ResilienceFaultRegistry();

        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        RedisScript<List> mockScript = mock(RedisScript.class);
        when(mockRedis.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(List.of(1L, "OK", 1L, 1L));

        // Global default is FAIL_CLOSED; experiment ONLY activates FAIL_OPEN.
        // If a reader ever saw redisFaultActive=true with scopedRedisPolicy=null (torn read),
        // it would fall back to global FAIL_CLOSED and throw WorkflowAdmissionException (503)!
        RedisCoordinationProperties props = new RedisCoordinationProperties();
        props.setFailurePolicy(AdmissionFailurePolicy.FAIL_CLOSED);
        props.setRetryAfterSeconds(5);

        RedisWorkflowAdmissionController controller = new RedisWorkflowAdmissionController(
                mockRedis, mockScript, mockScript, props,
                new RedisCoordinationMetrics(new SimpleMeterRegistry()), registry
        );

        int readerCount = 4;
        int writerIterations = 500;
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger tornReadErrors = new AtomicInteger(0);
        AtomicInteger totalReads = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);

        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < readerCount; r++) {
                final long userId = 100L + r;
                readers.add(executor.submit(() -> {
                    startLatch.await(2, TimeUnit.SECONDS);
                    while (running.get()) {
                        try {
                            AdmissionDecision decision = controller.tryAdmit(1L, userId);
                            if (!decision.admitted()) {
                                tornReadErrors.incrementAndGet();
                            }
                            totalReads.incrementAndGet();
                        } catch (WorkflowAdmissionException wae) {
                            // Torn read caused fallback to FAIL_CLOSED!
                            tornReadErrors.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            Future<?> writer = executor.submit(() -> {
                startLatch.countDown();
                for (int i = 0; i < writerIterations; i++) {
                    String expId = "exp-open-" + i;
                    registry.activateFault(ScenarioType.REDIS_INTERRUPTION, expId, "redis-iso", Map.of("policy", "FAIL_OPEN"));
                    registry.deactivateFault(ScenarioType.REDIS_INTERRUPTION, expId);
                }
                running.set(false);
                return null;
            });

            writer.get(10, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(5, TimeUnit.SECONDS);
            }

            assertEquals(0, tornReadErrors.get(), "Readers must NEVER observe torn policy state (FAIL_CLOSED fallback during FAIL_OPEN)");
            assertTrue(totalReads.get() > 0, "Readers must have executed admission checks concurrently");
            assertFalse(registry.isRedisFaultActive());
        } finally {
            executor.shutdownNow();
        }
    }
}
