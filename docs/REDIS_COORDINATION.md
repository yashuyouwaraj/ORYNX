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
If `tryAdmit` is invoked multiple times for the same workflow (e.g., retried HTTP request or duplicate consumer call), the Lua script detects that `workflowId` is already present in `orynx:admission:active` and returns `ALREADY_ADMITTED` without incrementing global or per-user counters.

Similarly, `release` is completely idempotent: removing a non-existent or already-released workflow ID returns `RELEASED` without negative count side-effects.

---

## 3. Configuration & Failure Policies

All coordination properties are mapped under `orynx.admission.*` in `application.properties`:

```properties
# Admission Control Properties
orynx.admission.enabled=true
orynx.admission.global-max-concurrent-workflows=100
orynx.admission.user-max-concurrent-workflows=20
orynx.admission.lease-ttl-seconds=7200
orynx.admission.failure-policy=FAIL_CLOSED
orynx.admission.reconciliation-cron=0 */5 * * * *
```

### Failure Policies: `FAIL_CLOSED` vs `FAIL_OPEN`

- **`FAIL_CLOSED` (Default / Production Recommended)**:
  - If Redis is unreachable, timeouts occur, or connection errors trigger, admission is **rejected** with a `WorkflowAdmissionException` (HTTP 503 Service Unavailable, `Retry-After: 15`).
  - Guarantees cluster resources are never overloaded during infrastructure degradation.
- **`FAIL_OPEN`**:
  - If Redis is unreachable, the admission controller logs an error, increments `orynx.workflow.admission.errors.total`, and **permits** the workflow to execute with a fallback token.
  - Prioritizes workflow availability over strict concurrency bounds.

### Client Backpressure Response

When a workflow is rejected due to capacity or quota limits, `GlobalExceptionHandler` converts `WorkflowAdmissionException` into a standardized REST response:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "reason": "GLOBAL_LIMIT_EXCEEDED",
  "message": "Workflow admission rejected: global execution concurrency limit reached (100/100)",
  "retryAfterSeconds": 15,
  "timestamp": 1732454400000
}
```

The HTTP response includes the standard header:
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 15
Content-Type: application/json
```

---

## 4. Key Namespaces & Redis Data Structures

All Redis keys used by OrynX coordination are strictly namespaced:

| Key Pattern | Redis Type | TTL | Purpose |
|---|---|---|---|
| `orynx:admission:active` | `Set<String>` | None (Managed) | Set containing active workflow IDs across the cluster |
| `orynx:admission:user:{userId}` | `Set<String>` | None (Managed) | Set containing active workflow IDs belonging to `userId` |
| `orynx:admission:lease:{workflowId}` | `String` | Configurable (`lease-ttl-seconds`, default 2h) | Lease holding owner token and admission timestamp |
| `orynx:lock:{lockKey}` | `String` | Explicit lease duration | Distributed mutex holding owner token |

---

## 5. Atomic Lua Scripts

To ensure strict ACID semantics in Redis across multi-key operations without distributed deadlocks or race conditions, all mutations use Redis Lua scripts compiled into `RedisScript<List<Object>>` or `RedisScript<Long>`.

### 5.1 `admit_workflow.lua`
- **Keys**: `orynx:admission:active`, `orynx:admission:user:{userId}`, `orynx:admission:lease:{workflowId}`
- **Args**: `workflowId`, `userId`, `globalMax`, `userMax`, `leaseTtlSeconds`, `token`
- **Logic**:
  1. Checks if `workflowId` is already in active set (`SISMEMBER`). If so, refreshes lease TTL and returns `ALREADY_ADMITTED`.
  2. Queries current cardinality (`SCARD`) of global and user sets.
  3. If global count >= `globalMax`, returns `GLOBAL_LIMIT_EXCEEDED`.
  4. If user count >= `userMax`, returns `USER_LIMIT_EXCEEDED`.
  5. Atomically adds `workflowId` to both sets (`SADD`) and writes lease key with TTL (`SETEX`).
  6. Returns `ADMITTED` with new global and user counts.

### 5.2 `release_workflow.lua`
- **Keys**: `orynx:admission:active`, `orynx:admission:user:{userId}`, `orynx:admission:lease:{workflowId}`
- **Args**: `workflowId`, `userId`
- **Logic**:
  1. Atomically removes `workflowId` from global set (`SREM`) and user set (`SREM`).
  2. Deletes lease key (`DEL`).
  3. Returns `RELEASED` with new global and user counts.

### 5.3 `release_lock.lua`
- **Keys**: `orynx:lock:{lockKey}`
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

## 7. Self-Healing Background Reconciliation

The `AdmissionReconciliationJob` runs on a scheduled cadence (default: every 5 minutes):
1. Acquires a distributed lock `orynx:lock:reconciliation-leader` for 4 minutes to ensure only one orchestrator instance reconciles at a time.
2. Scans all workflow IDs present in `orynx:admission:active`.
3. Queries PostgreSQL for each workflow's current status in the durable repository.
4. If PostgreSQL indicates the workflow is in a terminal state (`COMPLETED`, `FAILED`, `CANCELLED`) or missing, the job releases the admission slot in Redis.
5. Updates the Micrometer gauges to match true PostgreSQL and Redis state.

---

## 8. Observability & Alerting Integration

### Micrometer Metrics
- `orynx.workflow.admission.active.workflows` (Gauge): Current concurrent workflows in Redis.
- `orynx.workflow.admission.utilization` (Gauge): Global utilization ratio (`active / max`).
- `orynx.workflow.admission.admitted.total` (Counter): Workflows admitted successfully.
- `orynx.workflow.admission.rejected.total` (Counter, tagged `reason`): Workflows rejected due to limits.
- `orynx.workflow.admission.errors.total` (Counter): Redis communication errors during admission.
- `orynx.workflow.admission.reconciled.total` (Counter): Stale slots cleared by reconciliation.
- `orynx.redis.lock.acquired.total`, `orynx.redis.lock.failed.total`, `orynx.redis.lock.released.total`.

### Prometheus Alerts
- `RedisCoordinationErrorsHigh`: Triggers if Redis errors occur during admission control.
- `WorkflowAdmissionCapacitySaturated`: Triggers if global admission utilization exceeds 90% for > 5 minutes.
- `RedisUnavailable`: Critical alert when Redis is down; runbook documents admission backpressure impact.

### Grafana Dashboard
Dashboard `orynx-slo-reliability` contains Panel 17 displaying active admission slots, capacity utilization gauge, and admission rejection breakdown.
