# Resilience Engineering & Failure Injection Guide

OrynX incorporates a deterministic, bounded, and safe Resilience Engineering framework designed to validate the reliability, recoverability, and correctness of distributed workflow orchestration under infrastructure and component failures.

---

## 1. Resilience Architecture

The resilience framework is housed within `orchestrator-service` under `com.orynx.orchestrator.resilience`:

```
com.orynx.orchestrator.resilience/
├── adapter/                      # Failure injection SPI, scoped fault registry & component adapters
│   ├── FailureInjectionAdapter.java
│   ├── ResilienceFaultRegistry.java
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
├── model/                        # State machines, definitions, results & injection levels
│   ├── ExperimentDefinition.java
│   ├── ExperimentResult.java
│   ├── ExperimentState.java
│   ├── InjectionLevel.java
│   └── ScenarioType.java
├── reporting/                    # Markdown & JSON report generators
│   └── ResilienceReportGenerator.java
└── safety/                       # Isolation validation, server properties & blast-radius containment
    ├── ExperimentSafetyValidator.java
    ├── ResilienceProperties.java
    └── SafetyValidationException.java
```

---

## 2. Injection Levels & Adapter Classification Matrix

Failure injection adapters are explicitly classified into three honest injection tiers:

1. **`APPLICATION_SCOPED`**:
   - In-process fault injection intercepted via `ResilienceFaultRegistry` within application component boundaries (specifically `RedisWorkflowAdmissionController`).
   - Ensures zero global mutation of Spring singletons (e.g. `RedisCoordinationProperties.failurePolicy`), maintaining tenant isolation and thread safety.
2. **`ISOLATED_INFRASTRUCTURE`**:
   - Targets dedicated ephemeral infrastructure instances (e.g. standalone test Redis containers, mock brokers) isolated from shared cluster nodes.
3. **`SIMULATION`**:
   - Synthetic simulation of conditions such as cross-service latency degradation, simulated client drops, mock worker task expiration, or test harness parameter tracking without intercepting production paths.

### Adapter Classification Matrix

| Adapter | Scenario Type | Declared Injection Level | Interception Mechanism |
|---|---|---|---|
| `RedisInterruptionAdapter` | `REDIS_INTERRUPTION` | `APPLICATION_SCOPED` | In-process interception in `RedisWorkflowAdmissionController` via `ResilienceFaultRegistry` |
| `KafkaInterruptionAdapter` | `KAFKA_INTERRUPTION` | `SIMULATION` | Logical fault parameter registration in test harness (no live producer interception) |
| `PostgresInterruptionAdapter` | `POSTGRES_INTERRUPTION` | `SIMULATION` | Transaction rollback simulation parameters (no database connection pool poisoning) |
| `WorkerInterruptionAdapter` | `WORKER_INTERRUPTION` | `SIMULATION` | Heartbeat stall / lease expiry simulation parameters (no worker process termination) |
| `OrchestratorInterruptionAdapter` | `ORCHESTRATOR_INTERRUPTION` | `SIMULATION` | Standby promotion simulation parameters (no JVM or process termination) |
| `NetworkLatencyAdapter` | `NETWORK_LATENCY` | `SIMULATION` | Synthetic thread delay parameters (no kernel/tc network packet manipulation) |

---

## 3. Experiment Lifecycle & State Machine

Each experiment progresses through validated, deterministic state transitions:

```
[CREATED] ──> [VALIDATING] ──> [READY] ──> [RUNNING] ──> [OBSERVING] ──> [RECOVERING] ──> [COMPLETED]
     │              │             │           │              │               │
     ▼              ▼             ▼           ▼              ▼               ▼
 [ABORTED]      [FAILED]      [ABORTED]   [FAILED]       [FAILED]        [FAILED]
                                              │              │               │
                                              ├──────────────┼───────────────┼───> [TIMED_OUT]
                                              │              │               │
                                              └──────────────┴───────────────┴───> [CLEANUP_FAILED]
```

- **CREATED**: Experiment initialized and submitted to engine.
- **VALIDATING**: SafetyValidator inspects server configuration, environment tags, target resources, duration bounds, and acquires the cluster distributed lock.
- **READY**: Preconditions satisfied; adapter validated.
- **RUNNING**: Fault actively injected by adapter.
- **OBSERVING**: System behavior observed during failure duration; injection verified.
- **RECOVERING**: Component recovery initiated and recovery duration measured.
- **COMPLETED**: Normal state restored; verification assertions pass; cleanup succeeded.
- **FAILED**: Verification assertions or injection failed.
- **ABORTED**: Operator cancelled experiment; active execution thread interrupted.
- **TIMED_OUT**: Experiment exceeded `timeoutMs`; timeout watchdog actively interrupted execution thread.
- **CLEANUP_FAILED**: Teardown failed; quarantine mode activated; operator intervention required.

---

## 4. Safety Model & Containment Boundaries

To guarantee that failure injection never compromises shared environments or production data:

1. **Server-Side Authorization & Environment Model**:
   - Enforced by `ResilienceProperties` (`orynx.resilience.enabled` and `orynx.resilience.server-environment`).
   - If `orynx.resilience.enabled=false`, all experiments are immediately rejected.
   - If `server-environment` is `production` or contains `prod`, execution is rejected regardless of caller-supplied parameters.
   - Caller environment tag must match allowed environments (`test`, `resilience-isolated`, `staging`, `isolated`).
2. **Resource Whitelist & Blacklist**:
   - Target resources must match regex `^[a-zA-Z0-9][a-zA-Z0-9_\-\.:]{2,63}$`.
   - Blacklisted targets (e.g. `orynx-audit-postgres`, `orynx-kafka`, `orynx-audit-redis`, `localhost:5432`, `docker.sock`) are rejected with `SafetyValidationException`.
3. **Cluster Distributed Concurrency Lock & Stale-Owner Protection**:
   - Cluster-wide concurrency is coordinated via Step 24's `DistributedLockService` using mutex `orynx:{admission}:resilience_lock`.
   - Lock lease expiration is set dynamically to `timeoutMs + 30000ms`.
   - Ensures only **1 experiment** can execute across the entire distributed cluster simultaneously.
   - **Stale-Owner Protection**: Before injection, before recovery, and before cleanup, `ResilienceExperimentEngine` verifies active lock ownership via `distributedLockService.isLockHeldBy(lockKey, token)`.
     - If the lock lease expired or was re-acquired by a peer orchestrator instance during a prolonged stall, the engine immediately aborts execution by throwing `StaleLockOwnerException`.
     - Crucially, when lock ownership is lost, the engine **skips adapter cleanup** so that the stale node does not dismantle state or interfere with operations owned by the new lock holder.
     - *Design Note*: Checking `isLockHeldBy(lockKey, token)` verifies Redis lease key presence and value identity. It protects against stale lease reuse within the engine, but is a lease validation check rather than a monotonically increasing fencing token for downstream storage systems.
4. **Active Timeout Watchdog & Scheduler Lifecycle**:
   - A dedicated watchdog scheduler monitors `timeoutMs`. If an adapter or phase hangs, the watchdog interrupts the thread and transitions directly to cleanup.
   - Operator `abort(experimentId)` also actively interrupts the executing thread.
   - **Lifecycle Management**: The `ScheduledExecutorService timeoutScheduler` is bounded and managed via an explicit `@PreDestroy` method `public void shutdown()`. On Spring context shutdown, it calls `shutdownNow()` with a 2-second timeout to prevent lingering background threads and resource leaks during application termination or test context recycling.
5. **Authoritative Quarantine Mode & Fail-Closed Safety**:
   - Adapter `cleanup()` is executed in a Java `finally` block (provided lock ownership is still intact).
   - If cleanup fails, state transitions to `CLEANUP_FAILED`, and the engine enters **QUARANTINE MODE** locally and in Redis (`orynx:{admission}:resilience_quarantine`).
   - The quarantine state in Redis has no TTL; it persists indefinitely across restarts until an operator explicitly resets it.
   - **Fail-Closed on Redis Connectivity Failure**: During quarantine pre-flight checks (`verifyQuarantineSafety()` and `isQuarantined()`), if Redis is unreachable or returns a connectivity error, the engine **fails closed** by throwing `SafetyValidationException` ("Failed to verify quarantine status in Redis due to connectivity error... Failing closed to preserve cluster safety.") and `isQuarantined()` returns `true`. It never silently defaults to "unquarantined" when cluster state cannot be proven.
   - `clearQuarantine(operatorReason)` also fails loudly with `IllegalStateException` if Redis cannot be updated.
6. **Isolation Boundaries (Process-Level vs Multi-Tenant)**:
   - Live injection is strictly isolated to application-scoped faults via `ResilienceFaultRegistry` on non-production nodes.
   - The framework does not execute destructive OS-level commands (e.g. `docker kill`, `iptables`, `tc`, or `kill -9`).
   - In-process faults affect only threads interacting with the registered fault token in test environments, preventing cross-tenant pollution.

---

## 5. Prioritized Failure Scenarios

### Scenario A — Redis Interruption (`REDIS_INTERRUPTION`)
- **Focus**: Distributed admission control and lock reliability under Redis outage or latency.
- **Behavior**:
  - `FAIL_CLOSED` policy: Admission returns `AdmissionStatus.ERROR` with HTTP 503 and `Retry-After: 5` header, protecting cluster stability.
  - `FAIL_OPEN` policy: Logs warning and admits workflow to maintain throughput when configured.
  - Scoped faults in `ResilienceFaultRegistry` ensure live admission controllers observe the fault without mutating global Spring singletons.

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

## 6. Observability & Metrics

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

## 7. Execution & Reporting Guide

Resilience experiments can be initiated via `ResilienceExperimentEngine`:

```java
ExperimentDefinition def = ExperimentDefinition.builder()
    .id("exp-redis-failover-001")
    .name("Redis Isolation Test")
    .scenarioType(ScenarioType.REDIS_INTERRUPTION)
    .injectionLevel(InjectionLevel.APPLICATION_SCOPED)
    .targetComponent("redis")
    .targetResource("isolated-redis-node-1")
    .environmentTag("resilience-isolated")
    .durationMs(5000)
    .timeoutMs(15000)
    .parameters(Map.of("policy", "FAIL_CLOSED"))
    .expectedBehavior("Admission controller returns 503 Service Unavailable")
    .recoveryStrategy("Reconnect Redis client")
    .build();

ExperimentResult result = resilienceEngine.runExperiment(def);
String markdownReport = reportGenerator.generateMarkdownReport(def, result);
String jsonReport = reportGenerator.generateJsonReport(def, result);
```
