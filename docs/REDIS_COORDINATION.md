# OrynX — Distributed Coordination & Admission Control with Redis

## 1. Overview & Architecture

Step 24 establishes a production-oriented distributed coordination and admission-control subsystem in OrynX using Redis 7.

### Architecture Boundaries & Responsibilities

| Subsystem | Source of Truth | Mutability & Persistence | Key Responsibilities |
|---|---|---|---|
| **PostgreSQL 17** | **Durable System of Record** | Durable, ACID, persistent tables | Workflow state, task transitions, audit log, historical metrics, idempotency keys, terminal state |
| **Kafka KRaft** | **Event Streaming Pipeline** | Append-only event log, consumer groups | Decoupled workflow execution requests, task updates, cancellation/pause events |
| **Redis 7** | **Volatile Coordination Plane** | Ephemeral, in-memory, low-latency | Cluster-wide admission control, per-user concurrency quotas, distributed mutexes, active set tracking |

> **Crucial Invariant**: Redis is **NOT** a database for durable workflow state. It provides sub-millisecond atomic checks to protect cluster worker capacity and enforce fair user quotas. If Redis restarts or is flushed, PostgreSQL's durable state remains authoritative. An automated self-healing background reconciliation job continuously cross-checks and repairs Redis admission sets against PostgreSQL.

---

## 2. Admission Control Subsystem

### 2.1 The Need for Admission Control
In distributed orchestration, bursts of workflow creation (or large batch submissions) can exhaust worker pool threads, saturate Kafka broker partitions, and cause cascading failures. OrynX admission control intercepts workflow execution requests before they transition to `RUNNING` or dispatch to Kafka.

### 2.2 Admission Lifecycle Points
Admission control evaluates at three workflow activation entrypoints:
1. `WorkflowService.startWorkflow(workflowId, userId)`: Evaluates admission before transitioning workflow from `CREATED` to `RUNNING` and publishing `WorkflowExecutionRequestEvent`.
2. `WorkflowService.resumeWorkflow(workflowId, userId)`: Evaluates admission before resuming a `PAUSED` workflow.
3. `WorkflowService.retryWorkflow(workflowId, userId)`: Evaluates admission before re-executing a `FAILED` workflow.

Slots are automatically released at four lifecycle points:
1. `WorkflowService.cancelWorkflow(workflowId, userId)`: Releases slot when user explicitly cancels an active workflow.
2. `WorkflowService.pauseWorkflow(workflowId, userId)`: Releases slot when user explicitly pauses an active workflow.
3. `ExecutionEventConsumer.consumeWorkflowCompleted(event)`: Releases slot when a terminal `WorkflowCompletedEvent` arrives (whether `COMPLETED` or `FAILED`).
4. `ExecutionEventConsumer.consumeWorkflowExecutionPaused(event)`: Releases slot when execution worker yields or pauses.

### 2.3 Idempotency & Re-entrancy
If `tryAdmit` is invoked multiple times for the same workflow (e.g., retried HTTP request or duplicate consumer call), the Lua script detects that `workflowId` is already present in `orynx:{admission}:active_workflows` and returns `ALREADY_ADMITTED` without incrementing global or per-user counters.

Similarly, `release` is completely idempotent: removing a non-existent or already-released workflow ID returns `RELEASED` without negative count side-effects.

---

## 3. Configuration & Failure Policies

All coordination properties are mapped under `orynx.admission.*` in `application.properties`:

```properties
# Admission Control Properties
orynx.admission.enabled=true
orynx.admission.max-global-concurrent-workflows=100
orynx.admission.max-user-concurrent-workflows=10
orynx.admission.workflow-lease-ttl-seconds=3600
orynx.admission.failure-policy=FAIL_CLOSED
orynx.admission.reconciliation-interval-seconds=60
orynx.admission.inflight-grace-period-seconds=60
orynx.admission.retry-after-seconds=5
```

### Failure Policies: `FAIL_CLOSED` vs `FAIL_OPEN`

- **`FAIL_CLOSED` (Default / Production Recommended)**:
  - If Redis is unreachable, timeouts occur, or connection errors trigger, admission is **rejected** with a `WorkflowAdmissionException` (HTTP 503 Service Unavailable, `Retry-After: 5`).
  - Guarantees cluster resources are never overloaded during infrastructure degradation.
- **`FAIL_OPEN`**:
  - If Redis is unreachable, the admission controller logs an error, increments failure metrics, and **permits** the workflow to execute with a fallback token.
  - Prioritizes workflow availability over strict concurrency bounds.

### Client Backpressure Response

When a workflow is rejected due to capacity or quota limits, `GlobalExceptionHandler` converts `WorkflowAdmissionException` into a standardized REST response:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "reason": "GLOBAL_LIMIT_EXCEEDED",
  "message": "Workflow admission rejected: global execution concurrency limit reached (100/100)",
  "retryAfterSeconds": 5,
  "timestamp": 1732454400000
}
```

The HTTP response includes the standard header:
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 5
Content-Type: application/json
```

---

## 4. Key Namespaces, Hash Tags & Redis Data Structures

All Redis keys used by OrynX coordination use hash-tagging `{admission}` and `{lock}` to guarantee multi-node Redis Cluster compatibility without `CROSSSLOT` errors:

| Key Pattern | Redis Type | TTL | Purpose |
|---|---|---|---|
| `orynx:{admission}:active_workflows` | `Set<String>` | None (Managed) | Set containing active workflow IDs across the cluster |
| `orynx:{admission}:user_workflows:{userId}` | `Set<String>` | None (Managed) | Set containing active workflow IDs belonging to `userId` |
| `orynx:{admission}:lease:{workflowId}` | `String` | Configurable (`workflow-lease-ttl-seconds`) | Lease holding metadata `userId:leaseToken:admittedAtMs` |
| `orynx:{admission}:initialized` | `String` | 24 Hours | Marker key indicating Redis has been populated from PostgreSQL |
| `orynx:{lock}:{lockKey}` | `String` | Explicit lease duration | Distributed mutex holding owner token |

---

## 5. Atomic Lua Scripts

To ensure strict ACID semantics in Redis across multi-key operations without distributed deadlocks or race conditions, all mutations use Redis Lua scripts compiled into `RedisScript<List>` or `RedisScript<Long>`.

### 5.1 `admit_workflow.lua`
- **Keys**: `orynx:{admission}:active_workflows`, `orynx:{admission}:user_workflows:{userId}`, `orynx:{admission}:lease:{workflowId}`
- **Args**: `workflowId`, `maxGlobal`, `maxUser`, `ttlSeconds`, `leaseToken`, `userId`, `admittedAtMs`
- **Logic**:
  1. Checks if `workflowId` is already in active set (`SISMEMBER`). If so, refreshes lease TTL and returns `ALREADY_ADMITTED`.
  2. Queries current cardinality (`SCARD`) of global and user sets.
  3. If global count >= `maxGlobal`, returns `GLOBAL_LIMIT_EXCEEDED`.
  4. If user count >= `maxUser`, returns `USER_LIMIT_EXCEEDED`.
  5. Atomically adds `workflowId` to both sets (`SADD`) and writes lease key with metadata `${userId}:${leaseToken}:${admittedAtMs}` with TTL (`SETEX`).
  6. Sets safety TTL on user set (`ttlSeconds * 2`).
  7. Returns `ADMITTED` with new global and user counts.

### 5.2 `release_workflow.lua`
- **Keys**: `orynx:{admission}:active_workflows`, `orynx:{admission}:user_workflows:{fallbackUserId}`, `orynx:{admission}:lease:{workflowId}`
- **Args**: `workflowId`
- **Logic**:
  1. Atomically removes `workflowId` from global set (`SREM`).
  2. Inspects `lease:{workflowId}` to extract `realUserId` from stored metadata (`userId:leaseToken:admittedAtMs`).
  3. Removes `workflowId` from `orynx:{admission}:user_workflows:` .. realUserId (guaranteeing user quota sets never leak, even if caller passes `null` or mismatched user ID).
  4. Removes `workflowId` from fallback user set if provided.
  5. Deletes lease key (`DEL`).
  6. Returns `RELEASED` with new global and user counts.

### 5.3 `release_lock.lua`
- **Keys**: `orynx:{lock}:{lockKey}`
- **Args**: `expectedToken`
- **Logic**:
  1. Inspects current value of `lockKey`.
  2. If value matches `expectedToken`, deletes key (`DEL`) and returns 1.
  3. If value does not match (token expired or acquired by another instance), returns 0 without deleting.

---

## 6. Distributed Locking Subsystem

`DistributedLockService` provides an interface for token-fenced distributed mutexes:

```java
public interface DistributedLockService {
    boolean tryAcquire(String lockKey, String token, Duration leaseDuration);
    boolean release(String lockKey, String token);
    <T> Optional<T> executeWithLock(String lockKey, Duration leaseDuration, Supplier<T> action);
}
```

### Token Fencing Guarantee
Every lock acquisition generates a unique UUID token. Releasing the lock requires supplying the exact acquisition token. This prevents the classic "split-brain / slow GC" lock bug where a process delayed by a garbage collection pause releases a lock that has already been acquired by a different node.

---

## 7. Self-Healing Background Reconciliation & Cold-Restart Recovery

### 7.1 Leader Election
`AdmissionReconciliationJob` is leader-elected using `distributedLockService.executeWithLock("admission-reconciliation-leader", ...)`. Only one orchestrator instance reconciles across the cluster at any time, preventing split-brain lock fights and redundant database loads.

### 7.2 Cold-Restart State Reconstruction
On application boot (`@EventListener(ApplicationReadyEvent.class)`) or whenever `orynx:{admission}:initialized` is absent:
1. Acquires leader lock `"admission-recovery-leader"`.
2. Queries PostgreSQL durable repository for all workflows currently in `WorkflowStatus.RUNNING`.
3. Populates Redis sets (`active_workflows` and `user_workflows:{userId}`) and writes fresh lease keys.
4. If running workflows exceed `maxGlobalConcurrentWorkflows`, logs a critical error; cluster backpressure throttles new submissions until capacity frees up.
5. Sets `orynx:{admission}:initialized` with 24h TTL.

### 7.3 In-Flight `CREATED` Workflow Grace Period
During `startWorkflow`, a workflow is admitted in Redis while its PostgreSQL transaction is in-flight (status `CREATED`).
To prevent reconciliation from prematurely purging in-flight starting workflows:
- The lease key stores `admittedAtMs`.
- If a workflow in Redis is in status `CREATED` in PostgreSQL:
  - If `now - admittedAtMs < inflightGracePeriodSeconds` (default: 60s), the slot is **retained**.
  - If `now - admittedAtMs >= inflightGracePeriodSeconds`, it is identified as an abandoned transaction and **purged**.

---

## 8. Observability & Alerting Integration

### Micrometer Metrics
- `orynx.admission.active.workflows` (Gauge): Current concurrent workflows in Redis.
- `orynx.admission.utilization` (Gauge): Global utilization ratio (`active / max`).
- `orynx_workflow_admission_accepted_total`, `orynx_workflow_admission_rejected_total` (Counters tagged `reason`).
- `orynx_redis_operations_total` (Counter tagged `operation`, `status`).

### Prometheus Alerts
- `RedisCoordinationErrorsHigh`: Triggers if Redis operations fail (>5% error rate for 2m).
- `WorkflowAdmissionCapacitySaturated`: Triggers if global admission utilization exceeds 90% for 3m.
- `RedisUnavailable`: Critical alert when Redis is down; runbook documents admission backpressure impact.
