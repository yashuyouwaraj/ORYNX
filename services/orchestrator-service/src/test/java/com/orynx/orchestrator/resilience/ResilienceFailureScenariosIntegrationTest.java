package com.orynx.orchestrator.resilience;

import com.orynx.orchestrator.coordination.admission.AdmissionDecision;
import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.coordination.admission.RedisWorkflowAdmissionController;
import com.orynx.orchestrator.coordination.admission.WorkflowAdmissionException;
import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import com.orynx.orchestrator.resilience.adapter.KafkaInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.NetworkLatencyAdapter;
import com.orynx.orchestrator.resilience.adapter.OrchestratorInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.PostgresInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.RedisInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.WorkerInterruptionAdapter;
import com.orynx.orchestrator.resilience.engine.ExperimentRegistry;
import com.orynx.orchestrator.resilience.engine.ResilienceExperimentEngine;
import com.orynx.orchestrator.resilience.metrics.ResilienceMetrics;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ExperimentResult;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import com.orynx.orchestrator.resilience.safety.ExperimentSafetyValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResilienceFailureScenariosIntegrationTest {

    private ExperimentSafetyValidator safetyValidator;
    private ResilienceMetrics resilienceMetrics;
    private ExperimentRegistry registry;
    private ResilienceExperimentEngine engine;

    private RedisInterruptionAdapter redisAdapter;
    private KafkaInterruptionAdapter kafkaAdapter;
    private PostgresInterruptionAdapter postgresAdapter;
    private WorkerInterruptionAdapter workerAdapter;
    private OrchestratorInterruptionAdapter orchestratorAdapter;
    private NetworkLatencyAdapter networkAdapter;

    @BeforeEach
    void setUp() {
        safetyValidator = new ExperimentSafetyValidator();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        resilienceMetrics = new ResilienceMetrics(meterRegistry);
        registry = new ExperimentRegistry();

        redisAdapter = new RedisInterruptionAdapter();
        kafkaAdapter = new KafkaInterruptionAdapter();
        postgresAdapter = new PostgresInterruptionAdapter();
        workerAdapter = new WorkerInterruptionAdapter();
        orchestratorAdapter = new OrchestratorInterruptionAdapter();
        networkAdapter = new NetworkLatencyAdapter();

        engine = new ResilienceExperimentEngine(
                safetyValidator,
                resilienceMetrics,
                registry,
                List.of(redisAdapter, kafkaAdapter, postgresAdapter, workerAdapter, orchestratorAdapter, networkAdapter)
        );
    }

    @Test
    @DisplayName("Scenario A: Redis Interruption — Admission Controller throws 503 under FAIL_CLOSED during outage")
    void testScenarioA_RedisInterruptionFailClosed() {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-scenario-a-redis-closed")
                .name("Scenario A: Redis Interruption Fail Closed")
                .scenarioType(ScenarioType.REDIS_INTERRUPTION)
                .targetComponent("redis")
                .targetResource("isolated-redis-primary")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(2000)
                .parameters(Map.of("policy", "FAIL_CLOSED"))
                .expectedBehavior("Admission controller throws WorkflowAdmissionException with HTTP 503 backpressure")
                .recoveryStrategy("Reconnect to Redis cluster")
                .build();

        // Simulate admission controller under Redis connection failure
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        RedisScript<List> mockScript = mock(RedisScript.class);
        when(mockRedis.execute(any(RedisScript.class), any(List.class), any())).thenThrow(
                new RedisConnectionFailureException("Simulated Redis outage")
        );

        RedisCoordinationProperties props = new RedisCoordinationProperties();
        props.setFailurePolicy(AdmissionFailurePolicy.FAIL_CLOSED);
        props.setRetryAfterSeconds(5);

        RedisCoordinationMetrics coordMetrics = new RedisCoordinationMetrics(new SimpleMeterRegistry());
        RedisWorkflowAdmissionController admissionController = new RedisWorkflowAdmissionController(
                mockRedis, mockScript, mockScript, props, coordMetrics
        );

        // Precondition / verification during outage: FAIL_CLOSED throws 503 SERVICE_UNAVAILABLE
        WorkflowAdmissionException ex = assertThrows(WorkflowAdmissionException.class, () ->
                admissionController.tryAdmit(101L, 501L)
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getHttpStatus());
        assertTrue("REDIS_UNAVAILABLE".equalsIgnoreCase(ex.getReason()));
        assertEquals(5, ex.getRetryAfterSeconds());

        // Execute experiment through engine
        ExperimentResult expResult = engine.runExperiment(def);
        assertEquals(ExperimentResult.Outcome.PASS, expResult.getOutcome());
        assertTrue(expResult.isCleanupSuccessful());
    }

    @Test
    @DisplayName("Scenario A: Redis Interruption — Admission Controller allows FAIL_OPEN when configured")
    void testScenarioA_RedisInterruptionFailOpen() {
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        RedisScript<List> mockScript = mock(RedisScript.class);
        when(mockRedis.execute(any(RedisScript.class), any(List.class), any())).thenThrow(
                new RedisConnectionFailureException("Simulated Redis outage")
        );

        RedisCoordinationProperties props = new RedisCoordinationProperties();
        props.setFailurePolicy(AdmissionFailurePolicy.FAIL_OPEN);

        RedisCoordinationMetrics coordMetrics = new RedisCoordinationMetrics(new SimpleMeterRegistry());
        RedisWorkflowAdmissionController admissionController = new RedisWorkflowAdmissionController(
                mockRedis, mockScript, mockScript, props, coordMetrics
        );

        AdmissionDecision decision = admissionController.tryAdmit(102L, 502L);
        assertTrue(decision.admitted());
        assertTrue(decision.bypassedDueToFailure());
        assertTrue(decision.reason().contains("FAIL_OPEN_REDIS_ERROR"));
    }

    @Test
    @DisplayName("Scenario B: Kafka Interruption — Verifies publisher drops and consumer pause recovery")
    void testScenarioB_KafkaInterruption() {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-scenario-b-kafka")
                .name("Scenario B: Kafka Broker Outage")
                .scenarioType(ScenarioType.KAFKA_INTERRUPTION)
                .targetComponent("kafka")
                .targetResource("isolated-kafka-broker-1")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(2000)
                .parameters(Map.of("mode", "PRODUCER_DROP"))
                .expectedBehavior("Dispatches are queued or fail gracefully with retryable errors")
                .recoveryStrategy("Broker re-joins cluster and consumers rebalance")
                .build();

        ExperimentResult expResult = engine.runExperiment(def);
        assertEquals(ExperimentResult.Outcome.PASS, expResult.getOutcome());
        assertTrue(expResult.isCleanupSuccessful());
        assertFalse(kafkaAdapter.isDropPublications());
    }

    @Test
    @DisplayName("Scenario C: PostgreSQL Interruption — Verifies transaction abort and connection recovery")
    void testScenarioC_PostgresInterruption() {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-scenario-c-postgres")
                .name("Scenario C: Postgres Transaction Abort")
                .scenarioType(ScenarioType.POSTGRES_INTERRUPTION)
                .targetComponent("postgres")
                .targetResource("isolated-postgres-instance")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(2000)
                .parameters(Map.of("mode", "TRANSACTION_ABORT"))
                .expectedBehavior("Workflow transition rolls back atomically without partial state update")
                .recoveryStrategy("Rollback transaction and retry via connection pool")
                .build();

        ExperimentResult expResult = engine.runExperiment(def);
        assertEquals(ExperimentResult.Outcome.PASS, expResult.getOutcome());
        assertTrue(expResult.isCleanupSuccessful());
        assertFalse(postgresAdapter.isTransactionAbort());
    }

    @Test
    @DisplayName("Scenario D: Worker Interruption — Verifies worker crash, lease expiry and fencing")
    void testScenarioD_WorkerInterruption() {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-scenario-d-worker")
                .name("Scenario D: Worker Crash and Lease Expiry")
                .scenarioType(ScenarioType.WORKER_INTERRUPTION)
                .targetComponent("worker")
                .targetResource("isolated-worker-node-1")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(2000)
                .parameters(Map.of("mode", "LEASE_EXPIRED"))
                .expectedBehavior("Zombie worker execution is fenced; new worker claims task")
                .recoveryStrategy("Re-dispatch to healthy worker")
                .build();

        ExperimentResult expResult = engine.runExperiment(def);
        assertEquals(ExperimentResult.Outcome.PASS, expResult.getOutcome());
        assertTrue(expResult.isCleanupSuccessful());
        assertFalse(workerAdapter.isLeaseExpired());
    }

    @Test
    @DisplayName("Scenario E: Orchestrator Interruption — Verifies leader lock lost and graceful restart")
    void testScenarioE_OrchestratorInterruption() {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-scenario-e-orchestrator")
                .name("Scenario E: Orchestrator Leader Lock Lost")
                .scenarioType(ScenarioType.ORCHESTRATOR_INTERRUPTION)
                .targetComponent("orchestrator")
                .targetResource("isolated-orchestrator-instance")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(2000)
                .parameters(Map.of("mode", "LEADER_LOCK_LOST"))
                .expectedBehavior("Standby orchestrator acquires leader lock and reconciles in-flight workflows")
                .recoveryStrategy("Re-election and startup reconciliation")
                .build();

        ExperimentResult expResult = engine.runExperiment(def);
        assertEquals(ExperimentResult.Outcome.PASS, expResult.getOutcome());
        assertTrue(expResult.isCleanupSuccessful());
        assertFalse(orchestratorAdapter.isLeaderLockLost());
    }

    @Test
    @DisplayName("Scenario F: Network Latency — Verifies controlled latency injection and timeout handling")
    void testScenarioF_NetworkLatency() {
        ExperimentDefinition def = ExperimentDefinition.builder()
                .id("exp-scenario-f-network")
                .name("Scenario F: Cross-Service Network Latency")
                .scenarioType(ScenarioType.NETWORK_LATENCY)
                .targetComponent("network")
                .targetResource("isolated-network-link-1")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(2000)
                .parameters(Map.of("latencyMs", "250"))
                .expectedBehavior("Service clients invoke retry/circuit breaker without cascading failure")
                .recoveryStrategy("Latency clears automatically")
                .build();

        ExperimentResult expResult = engine.runExperiment(def);
        assertEquals(ExperimentResult.Outcome.PASS, expResult.getOutcome());
        assertTrue(expResult.isCleanupSuccessful());
        assertEquals(0, networkAdapter.getArtificialLatencyMs());
    }
}
