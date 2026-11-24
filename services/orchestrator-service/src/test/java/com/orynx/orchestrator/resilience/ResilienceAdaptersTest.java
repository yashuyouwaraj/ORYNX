package com.orynx.orchestrator.resilience;

import com.orynx.orchestrator.resilience.adapter.KafkaInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.NetworkLatencyAdapter;
import com.orynx.orchestrator.resilience.adapter.OrchestratorInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.PostgresInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.RedisInterruptionAdapter;
import com.orynx.orchestrator.resilience.adapter.WorkerInterruptionAdapter;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilienceAdaptersTest {

    private ExperimentDefinition baseDef(ScenarioType scenario, Map<String, String> params) {
        return ExperimentDefinition.builder()
                .id("exp-test-001")
                .name("Adapter Test")
                .scenarioType(scenario)
                .targetComponent(scenario.getDefaultComponent())
                .targetResource("isolated-resource-1")
                .environmentTag("resilience-isolated")
                .durationMs(100)
                .timeoutMs(1000)
                .parameters(params)
                .build();
    }

    @Test
    @DisplayName("RedisInterruptionAdapter injects, verifies, recovers and cleans up")
    void testRedisAdapter() {
        RedisInterruptionAdapter adapter = new RedisInterruptionAdapter();
        ExperimentDefinition def = baseDef(ScenarioType.REDIS_INTERRUPTION, Map.of("policy", "FAIL_CLOSED"));

        adapter.validate(def);
        assertFalse(adapter.isSimulatedFailureActive());

        adapter.inject(def);
        assertTrue(adapter.verifyInjected(def));
        assertTrue(adapter.isSimulatedFailureActive());

        adapter.recover(def);
        assertTrue(adapter.verifyRecovered(def));
        assertFalse(adapter.isSimulatedFailureActive());

        adapter.cleanup(def);
        assertFalse(adapter.verifyInjected(def));
    }

    @Test
    @DisplayName("KafkaInterruptionAdapter handles producer drop and consumer pause")
    void testKafkaAdapter() {
        KafkaInterruptionAdapter adapter = new KafkaInterruptionAdapter();
        ExperimentDefinition defDrop = baseDef(ScenarioType.KAFKA_INTERRUPTION, Map.of("mode", "PRODUCER_DROP"));

        adapter.validate(defDrop);
        adapter.inject(defDrop);
        assertTrue(adapter.verifyInjected(defDrop));
        assertTrue(adapter.isDropPublications());

        adapter.recover(defDrop);
        assertTrue(adapter.verifyRecovered(defDrop));
        assertFalse(adapter.isDropPublications());

        adapter.cleanup(defDrop);

        // Test consumer pause
        ExperimentDefinition defPause = baseDef(ScenarioType.KAFKA_INTERRUPTION, Map.of("mode", "CONSUMER_PAUSE"));
        adapter.inject(defPause);
        assertTrue(adapter.isPauseConsumers());
        adapter.recover(defPause);
        assertFalse(adapter.isPauseConsumers());
    }

    @Test
    @DisplayName("PostgresInterruptionAdapter handles connection down and transaction abort")
    void testPostgresAdapter() {
        PostgresInterruptionAdapter adapter = new PostgresInterruptionAdapter();
        ExperimentDefinition def = baseDef(ScenarioType.POSTGRES_INTERRUPTION, Map.of("mode", "CONNECTION_DOWN"));

        adapter.validate(def);
        adapter.inject(def);
        assertTrue(adapter.verifyInjected(def));
        assertTrue(adapter.isDbDown());

        adapter.recover(def);
        assertTrue(adapter.verifyRecovered(def));
        assertFalse(adapter.isDbDown());

        adapter.cleanup(def);
    }

    @Test
    @DisplayName("WorkerInterruptionAdapter handles worker crash and lease expiration")
    void testWorkerAdapter() {
        WorkerInterruptionAdapter adapter = new WorkerInterruptionAdapter();
        ExperimentDefinition def = baseDef(ScenarioType.WORKER_INTERRUPTION, Map.of("mode", "WORKER_CRASH"));

        adapter.validate(def);
        adapter.inject(def);
        assertTrue(adapter.verifyInjected(def));
        assertTrue(adapter.isWorkerCrashed());

        adapter.recover(def);
        assertTrue(adapter.verifyRecovered(def));
        assertFalse(adapter.isWorkerCrashed());

        adapter.cleanup(def);
    }

    @Test
    @DisplayName("OrchestratorInterruptionAdapter handles leader lock lost and restart")
    void testOrchestratorAdapter() {
        OrchestratorInterruptionAdapter adapter = new OrchestratorInterruptionAdapter();
        ExperimentDefinition def = baseDef(ScenarioType.ORCHESTRATOR_INTERRUPTION, Map.of("mode", "LEADER_LOCK_LOST"));

        adapter.validate(def);
        adapter.inject(def);
        assertTrue(adapter.verifyInjected(def));
        assertTrue(adapter.isLeaderLockLost());

        adapter.recover(def);
        assertTrue(adapter.verifyRecovered(def));
        assertFalse(adapter.isLeaderLockLost());

        adapter.cleanup(def);
    }

    @Test
    @DisplayName("NetworkLatencyAdapter introduces and clears artificial latency")
    void testNetworkLatencyAdapter() {
        NetworkLatencyAdapter adapter = new NetworkLatencyAdapter();
        ExperimentDefinition def = baseDef(ScenarioType.NETWORK_LATENCY, Map.of("latencyMs", "500"));

        adapter.validate(def);
        adapter.inject(def);
        assertTrue(adapter.verifyInjected(def));
        assertEquals(500, adapter.getArtificialLatencyMs());

        adapter.recover(def);
        assertTrue(adapter.verifyRecovered(def));
        assertEquals(0, adapter.getArtificialLatencyMs());

        adapter.cleanup(def);
    }

    @Test
    @DisplayName("All adapters declare truthful and verified injection levels (M-01)")
    void testAdapterInjectionLevels() {
        assertEquals(com.orynx.orchestrator.resilience.model.InjectionLevel.APPLICATION_SCOPED,
                new RedisInterruptionAdapter().getInjectionLevel());
        assertEquals(com.orynx.orchestrator.resilience.model.InjectionLevel.SIMULATION,
                new KafkaInterruptionAdapter().getInjectionLevel());
        assertEquals(com.orynx.orchestrator.resilience.model.InjectionLevel.SIMULATION,
                new PostgresInterruptionAdapter().getInjectionLevel());
        assertEquals(com.orynx.orchestrator.resilience.model.InjectionLevel.SIMULATION,
                new WorkerInterruptionAdapter().getInjectionLevel());
        assertEquals(com.orynx.orchestrator.resilience.model.InjectionLevel.SIMULATION,
                new OrchestratorInterruptionAdapter().getInjectionLevel());
        assertEquals(com.orynx.orchestrator.resilience.model.InjectionLevel.SIMULATION,
                new NetworkLatencyAdapter().getInjectionLevel());
    }

    @Test
    @DisplayName("neutralizeLocalFault deactivates fault in registry across all adapters (C-01)")
    void testNeutralizeLocalFaultAcrossAllAdapters() {
        com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry registry =
                new com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry();

        RedisInterruptionAdapter redis = new RedisInterruptionAdapter(registry);
        ExperimentDefinition redisDef = baseDef(ScenarioType.REDIS_INTERRUPTION, Map.of("policy", "FAIL_CLOSED"));
        redis.inject(redisDef);
        assertTrue(registry.isRedisFaultActive());
        redis.neutralizeLocalFault(redisDef);
        assertFalse(registry.isRedisFaultActive());

        KafkaInterruptionAdapter kafka = new KafkaInterruptionAdapter(registry);
        ExperimentDefinition kafkaDef = baseDef(ScenarioType.KAFKA_INTERRUPTION, Map.of("mode", "PRODUCER_DROP"));
        kafka.inject(kafkaDef);
        assertTrue(registry.isFaultActive(ScenarioType.KAFKA_INTERRUPTION));
        kafka.neutralizeLocalFault(kafkaDef);
        assertFalse(registry.isFaultActive(ScenarioType.KAFKA_INTERRUPTION));

        PostgresInterruptionAdapter postgres = new PostgresInterruptionAdapter(registry);
        ExperimentDefinition pgDef = baseDef(ScenarioType.POSTGRES_INTERRUPTION, Map.of("mode", "CONNECTION_DOWN"));
        postgres.inject(pgDef);
        assertTrue(registry.isFaultActive(ScenarioType.POSTGRES_INTERRUPTION));
        postgres.neutralizeLocalFault(pgDef);
        assertFalse(registry.isFaultActive(ScenarioType.POSTGRES_INTERRUPTION));

        WorkerInterruptionAdapter worker = new WorkerInterruptionAdapter(registry);
        ExperimentDefinition workerDef = baseDef(ScenarioType.WORKER_INTERRUPTION, Map.of("mode", "WORKER_CRASH"));
        worker.inject(workerDef);
        assertTrue(registry.isFaultActive(ScenarioType.WORKER_INTERRUPTION));
        worker.neutralizeLocalFault(workerDef);
        assertFalse(registry.isFaultActive(ScenarioType.WORKER_INTERRUPTION));

        OrchestratorInterruptionAdapter orch = new OrchestratorInterruptionAdapter(registry);
        ExperimentDefinition orchDef = baseDef(ScenarioType.ORCHESTRATOR_INTERRUPTION, Map.of("mode", "LEADER_LOCK_LOST"));
        orch.inject(orchDef);
        assertTrue(registry.isFaultActive(ScenarioType.ORCHESTRATOR_INTERRUPTION));
        orch.neutralizeLocalFault(orchDef);
        assertFalse(registry.isFaultActive(ScenarioType.ORCHESTRATOR_INTERRUPTION));

        NetworkLatencyAdapter net = new NetworkLatencyAdapter(registry);
        ExperimentDefinition netDef = baseDef(ScenarioType.NETWORK_LATENCY, Map.of("latencyMs", "500"));
        net.inject(netDef);
        assertTrue(registry.isFaultActive(ScenarioType.NETWORK_LATENCY));
        net.neutralizeLocalFault(netDef);
        assertFalse(registry.isFaultActive(ScenarioType.NETWORK_LATENCY));
    }
}
