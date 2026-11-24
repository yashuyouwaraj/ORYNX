# Resilience Engineering & Failure Injection Guide

OrynX incorporates a deterministic, bounded, and safe Resilience Engineering framework designed to validate the reliability, recoverability, and correctness of distributed workflow orchestration under infrastructure and component failures.

---

## 1. Resilience Architecture

The resilience framework is housed within `orchestrator-service` under `com.orynx.orchestrator.resilience`:

```
com.orynx.orchestrator.resilience/
├── adapter/                      # Failure injection SPI and component adapters
│   ├── FailureInjectionAdapter.java
│   ├── RedisInterruptionAdapter.java
│   ├── KafkaInterruptionAdapter.java
│   ├── PostgresInterruptionAdapter.java
│   ├── WorkerInterruptionAdapter.java
│   ├── OrchestratorInterruptionAdapter.java
│   └── NetworkLatencyAdapter.java
├── engine/                       # Core execution orchestrator & thread-safe registry
│   ├── ExperimentRegistry.java
│   └── ResilienceExperimentEngine.java
├── metrics/                      # Bounded-cardinality Micrometer metrics
│   └── ResilienceMetrics.java
├── model/                        # State machines, definitions, and results
│   ├── ExperimentDefinition.java
│   ├── ExperimentResult.java
│   ├── ExperimentState.java
│   └── ScenarioType.java
├── reporting/                    # Markdown & JSON report generators
│   └── ResilienceReportGenerator.java
└── safety/                       # Isolation validation and blast-radius containment
    ├── ExperimentSafetyValidator.java
    └── SafetyValidationException.java
```

---

## 2. Experiment Lifecycle & State Machine

Each experiment progresses through validated, deterministic state transitions:

```
[CREATED] ──> [VALIDATING] ──> [READY] ──> [RUNNING] ──> [OBSERVING] ──> [RECOVERING] ──> [COMPLETED]
     │              │             │           │              │               │
     ▼              ▼             ▼           ▼              ▼               ▼
 [ABORTED]      [FAILED]      [ABORTED]   [FAILED]       [FAILED]        [FAILED]
                                              │              │               │
                                              └──────────────┴───────────────┴───> [CLEANUP_FAILED]
```

- **CREATED**: Experiment initialized and submitted to engine.
- **VALIDATING**: SafetyValidator inspects environment tag, target resource, duration bounds, and concurrency locks.
- **READY**: Preconditions satisfied; adapter validated.
- **RUNNING**: Fault actively injected by adapter.
- **OBSERVING**: System behavior observed during failure duration; injection verified.
- **RECOVERING**: Component recovery initiated and recovery duration measured.
- **COMPLETED**: Normal state restored; verification assertions pass; cleanup succeeded.
- **FAILED**: Verification assertions or injection failed.
- **ABORTED**: Operator or safety watchdog cancelled experiment.
- **CLEANUP_FAILED**: Teardown failed; operator intervention required.

---

## 3. Safety Model & Containment Boundaries

To guarantee that failure injection never compromises shared environments or production data:

1. **Environment Whitelisting**:
   - Experiments are strictly permitted only in `test`, `resilience-isolated`, `staging`, or `isolated` environments.
   - Any tag matching `prod`, `production`, `default`, `shared`, or `live` is immediately rejected.
2. **Resource Whitelist & Blacklist**:
   - Target resources must match regex `^[a-zA-Z0-9][a-zA-Z0-9_\-\.:]{2,63}$`.
   - Blacklisted targets (e.g. `orynx-audit-postgres`, `orynx-kafka`, `orynx-audit-redis`, `localhost:5432`, `docker.sock`) are rejected with `SafetyValidationException`.
3. **Bounded Duration & Timeouts**:
   - Minimum duration: 50 ms.
   - Maximum duration: 120,000 ms (2 minutes).
   - Maximum timeout: 300,000 ms (5 minutes).
4. **Concurrency Enforcement**:
   - Atomic concurrency guard limits node execution to exactly **1 concurrent experiment**.
5. **Guaranteed Teardown**:
   - Adapter `cleanup()` is executed in a Java `finally` block.
   - If cleanup fails, state transitions to `CLEANUP_FAILED`, an alert-level metric is incremented, and result is marked `CLEANUP_FAILED`.

---

## 4. Prioritized Failure Scenarios

### Scenario A — Redis Interruption (`REDIS_INTERRUPTION`)
- **Focus**: Distributed admission control and lock reliability under Redis outage or latency.
- **Behavior**:
  - `FAIL_CLOSED` policy: Admission returns `AdmissionStatus.ERROR` with HTTP 503 and `Retry-After: 5` header, protecting cluster stability.
  - `FAIL_OPEN` policy: Logs warning and admits workflow to maintain throughput when configured.
  - Distributed lock operations fail gracefully without acquiring locks.
  - Upon reconnection, `AdmissionReconciliationJob` reconstructs cluster counts against PostgreSQL ground truth.

### Scenario B — Kafka Interruption (`KAFKA_INTERRUPTION`)
- **Focus**: Event publication drops and consumer lag accumulation.
- **Behavior**:
  - Dispatch attempts retry with backoff.
  - In-flight workflow state remains consistent in database; no partial status advancement without dispatched events.
  - Upon broker re-join, consumer groups rebalance and resume processing.

### Scenario C — PostgreSQL Interruption (`POSTGRES_INTERRUPTION`)
- **Focus**: Transaction rollbacks and admission slot consistency.
- **Behavior**:
  - Failed database transactions trigger rollback of corresponding Redis admission slots to prevent admission leaks.
  - Connection pool recovers automatically upon database restoration.

### Scenario D — Execution Worker Interruption (`WORKER_INTERRUPTION`)
- **Focus**: Worker heartbeat stall, task lease expiration, and fencing tokens.
- **Behavior**:
  - Unacknowledged leases expire in Redis.
  - Orphaned tasks are detected by scheduler and re-dispatched to healthy workers.
  - Fencing tokens prevent duplicate task execution if zombie worker resumes.

### Scenario E — Orchestrator Interruption (`ORCHESTRATOR_INTERRUPTION`)
- **Focus**: Leader lock loss and startup reconciliation.
- **Behavior**:
  - Standby orchestrator node acquires leader lock upon lease expiry.
  - Startup reconciliation synchronizes active workflows and releases orphaned admission slots.

### Scenario F — Network Latency & Interruption (`NETWORK_LATENCY`)
- **Focus**: Controlled cross-service latency degradation.
- **Behavior**:
  - Verifies HTTP client timeouts, circuit breakers, and retry policies.
  - Bounded artificial delays (10ms to 10,000ms) clear deterministically upon recovery.

---

## 5. Observability & Metrics

Resilience telemetry uses bounded-cardinality Micrometer metrics pre-registered at startup:

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `orynx.resilience.experiments.total` | Counter | `scenario`, `outcome` | Total resilience experiments executed |
| `orynx.resilience.injections.total` | Counter | `scenario`, `status` | Total failure injections attempted |
| `orynx.resilience.recovery.duration.seconds` | Timer | `scenario` | Time taken for target to recover |
| `orynx.resilience.cleanup.failures.total` | Counter | `scenario` | Total failed experiment cleanups |
| `orynx.resilience.active.experiments` | Gauge | none | Current running experiment count |

*Note: High-cardinality values such as experiment IDs, workflow IDs, or hostnames are never included as Prometheus metric labels.*

---

## 6. Execution & Reporting Guide

Resilience experiments can be initiated via `ResilienceExperimentEngine`:

```java
ExperimentDefinition def = ExperimentDefinition.builder()
    .id("exp-redis-failover-001")
    .name("Redis Primary Outage Test")
    .scenarioType(ScenarioType.REDIS_INTERRUPTION)
    .targetComponent("redis")
    .targetResource("isolated-redis-primary")
    .environmentTag("resilience-isolated")
    .durationMs(1000)
    .timeoutMs(5000)
    .parameters(Map.of("policy", "FAIL_CLOSED"))
    .expectedBehavior("Reject admission with 503 backpressure")
    .recoveryStrategy("Restore Redis connectivity")
    .build();

ExperimentResult result = engine.runExperiment(def);
String markdown = reportGenerator.generateMarkdownReport(def, result);
String json = reportGenerator.generateJsonReport(def, result);
```

Every generated report contains the 17 mandatory audit fields:
1. Experiment ID
2. Scenario
3. Environment
4. Target
5. Preconditions
6. Injection Parameters
7. Start Timestamp
8. Failure Duration
9. Observed Behavior
10. Workflow Impact
11. Recovery Duration
12. Recovery Validation
13. Metrics and Alerts
14. Cleanup Status
15. Result (`PASS`, `FAIL`, `ABORTED`, `INCONCLUSIVE`, `CLEANUP_FAILED`)
16. Failure Details
17. Reproduction Instructions

---

## 7. Limitations & Unsupported Scenarios

1. **Kernel/OS Level Panics**: Kernel crashes, host kernel panics, or OS hardware reboots are out of scope.
2. **Arbitrary Host Docker Execution**: Executing arbitrary Docker commands against host daemon is strictly forbidden by the safety model.
3. **Multi-Region Cross-Cloud Partitions**: Experiments validate single-cluster and multi-node failure domains; multi-cloud WAN cuts are simulated via network latency adapter.
