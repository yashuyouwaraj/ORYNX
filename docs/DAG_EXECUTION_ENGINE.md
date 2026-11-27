# OrynX DAG Workflow Execution Engine (Step 28)

## 1. Overview
Step 28 upgrades OrynX from a sequential simulated loop to a **bounded, deterministic, parallel Directed Acyclic Graph (DAG) workflow execution engine** with durable task-level checkpointing, crash recovery, pluggable task handlers, and DAG visualization.

---

## 2. DAG Domain Model & Validation (`orchestrator-service`)
Workflow creation (`POST /api/v1/workflows`) accepts an optional `tasks` array where each task specifies:
- `taskKey` (`^[A-Za-z0-9._-]{1,64}$`, unique per workflow)
- `name` (required)
- `taskType`: `NOOP` (default), `ECHO`, `TRANSFORM`, or `SIMULATED`
- `dependsOn`: array of upstream `taskKey`s
- `config`: structured key/value map
- `timeoutMs`: bounded `[100..300000]` (default `30000`)
- `maxRetries`: bounded `[0..10]` (default `2`)

### Kahn's Algorithm Validation & Payload Bounds (`DagValidator`)
Rejected with `HTTP 400` prior to persistence:
- Cycles, self-dependencies, duplicate `taskKey`s, nonexistent dependencies
- Maximum nodes: `100`
- Maximum dependency edges: `500`
- Maximum graph depth: `32`
- Maximum task `config` serialized size: `16,384` bytes (`16 KB`), max nesting depth `8`, max top-level keys `64`
- Maximum total workflow `config` payload across all tasks: `262,144` bytes (`256 KB`)
- Unsupported `taskType` or out-of-bounds `timeoutMs` / `maxRetries`
- Disconnected DAG components are permitted.

### Backward Compatibility
- Requests omitting `tasks` create the legacy 6-task workflow mapped to a linear chain (`task-1 → ... → task-6`, `taskType=NOOP`).
- Requests providing `tasks` without `dependsOn` map sequentially by `executionOrder`.
- Normalized edges are stored in `workflow_task_dependencies` alongside `workflow_tasks`.

---

## 3. Pluggable Task Handlers (`execution-service`)
- **`NOOP`**: Deterministic success with optional bounded `delayMs` (`0..5000ms`).
- **`ECHO`**: Returns validated `config` and `upstreamOutputs`.
- **`TRANSFORM`**: Narrowly scoped, deterministic operations (`UPPERCASE`, `LOWERCASE`, `CONCAT`, `PICK_KEYS`, `MERGE_UPSTREAM`) — zero scripting, expression evaluation, or shell execution.
- **`SIMULATED`**: Opt-in handler for resilience testing (`delayMs` `0..10000ms`, `failureProbability` `0.0..1.0`, `forceFail`, `failUntilAttempt`).
- **`HTTP_CALL` Security Boundary**: Intentionally **deferred** from Step 28 rather than shipping without full DNS-rebinding/redirect/private-IP SSRF validation and dedicated security audit.

---

## 4. Bounded Parallel DAG Scheduler & Durable Checkpointing
- **Deadlock-Free Architecture**: `DagExecutionCoordinator` schedules eligible DAG tasks on a dedicated `dagTaskExecutor` (separate from `workflowExecutionExecutor`) using a non-blocking ready queue (`ConcurrentLinkedQueue`) and atomic fan-in counters (`AtomicInteger remainingDeps`, `AtomicBoolean dispatched`).
- **Concurrency & Payload Limits (`application.properties`)**:
  - `orynx.execution.dag.core-pool-size=8`
  - `orynx.execution.dag.max-pool-size=16`
  - `orynx.execution.dag.queue-capacity=100`
  - `orynx.execution.dag.max-per-workflow-concurrency=4`
  - `orynx.execution.dag.max-task-config-bytes=16384`
  - `orynx.execution.dag.max-task-output-bytes=32768`
  - `orynx.execution.dag.max-payload-nesting-depth=8`
  - `orynx.execution.dag.max-transform-parts=256`
- **Scheduler Rejection Liveness**: On `RejectedExecutionException` in `drainReadyQueue`, the coordinator decrements `inFlight`, transitions the rejected node to `FAILED` (and transitive dependents to `SKIPPED`), explicitly fails all queued nodes on executor shutdown, and guarantees `completionFuture` settlement without hangs or unbounded retry loops.
- **Per-Task Timeout & Isolation Semantics**:
  - Lightweight 2-thread `timeoutScheduler` triggers only atomic `AttemptOutcome` CAS (`RUNNING -> COMPLETED | TIMED_OUT`), `workerThread.interrupt()`, and offloads final-attempt durable settlement onto a dedicated bounded `timeoutSettlementExecutor` (`2..4` threads, bounded queue `64`, `AbortPolicy`) so blocking PostgreSQL/Kafka I/O never stalls timer threads.
  - `settleFinalAttemptTimeout` is fully exception-safe: `isFenced` or `StaleWorkerExecutionException` sets `state.fenced=true` and suppresses terminal workflow events; transient DB/Kafka errors or settlement pool rejection (`RejectedExecutionException`) mark the node terminal and workflow `FAILED`; `finally` guarantees idempotent `slotReleased` release and `drainReadyQueue` settlement so `completionFuture` can never hang.
  - **Limitation**: Java cannot forcibly terminate an uninterruptible thread; while DAG state settles cleanly and late commits are fenced, a non-interruptible handler still occupies its pool thread until it exits.
- **Durable Checkpoints (`workflow_task_checkpoints`)**:
  - Unique key `(workflow_id, task_key)` with `PESSIMISTIC_WRITE` locking in `TaskCheckpointService`.
  - **First-Insert Race Safety**: Concurrent first inserts on `(workflow_id, task_key)` are handled via bounded `REQUIRES_NEW` transaction retries (`MAX_INSERT_RACE_ATTEMPTS=3`) using `saveAndFlush`, so the retry acquires the row lock and enforces generation fencing and `COMPLETED` protection.
  - **Generation Fencing**: Stale generations (`effectiveGen < existing.executionGeneration`) fail with `StaleWorkerExecutionException`.
  - **Semantics**: At-least-once task execution with fenced idempotent checkpoints (not exactly-once execution).
- **Explicit Execution Modes (`WorkflowExecutionRequestEvent.executionMode`)**:
  - **`INITIAL`**: Fresh run (`startWorkflow`); resets any prior checkpoints for the workflow.
  - **`RETRY`**: User-initiated retry (`retryWorkflow`); resets prior checkpoints so tasks reset to `PENDING` in `orchestrator-service` are never silently skipped. Never inferred from `generation > 1`.
  - **`RECOVERY`**: Lease-expiry crash recovery — triggered both by `ExecutionRecoveryScanner` AND by `ExecutionRequestConsumer` whenever `ExecutionCoordinationService.tryAcquireLease` returns `reclaimedExpiredLease=true` (reclaiming an expired `DISPATCHED`/`RUNNING` lease via Kafka redelivery). Loads `COMPLETED` checkpoints, restores `outputJson`, and schedules only the incomplete durable frontier.
  - **`RESUME`**: User-initiated resume (`resumeWorkflow`); for DAG workflows, `orchestrator-service` checks task statuses (never `lastCompletedExecutionOrder`), publishes the full DAG graph with `completedTaskKeys`, and `execution-service` hydrates `COMPLETED` checkpoints + upstream outputs to execute only incomplete tasks.
- **Completion Watermark (`lastCompletedExecutionOrder`)**: Retained strictly for presentation and legacy linear compatibility. For DAG workflows, `ExecutionEventConsumer` and `DagExecutionCoordinator` advance it only to the highest contiguous completed `executionOrder` prefix so out-of-order parallel siblings never imply lower-order tasks are complete.
- **Failure Propagation**: When a branch task exhausts retries, transitive downstream dependents are deterministically marked `SKIPPED` (persisted in checkpoints and emitted via `TaskCompletedEvent(skipped=true)`).

---

## 5. Observability & Frontend DAG Rendering
- **Tracing**: Parallel task spans (`orynx.task.execute`) inherit the parent workflow trace context and record `orynx.task.key`, `orynx.task.type`, `orynx.task.attempt`, and `orynx.execution.generation`.
- **Bounded Metrics (`ExecutionMetrics`)**: `orynx_dag_tasks_total{task_type, outcome}`, `orynx_dag_task_duration_seconds{task_type, outcome}`, `orynx_dag_active_tasks`, `orynx_dag_checkpoint_duration_seconds`, `orynx_dag_recoveries_total`, `orynx_dag_task_timeouts_total`.
- **Frontend**: `getWorkflowTasks` (`frontend/src/services/api.ts`), `mapWorkflowTasksToDagGraph`, `resolveWorkflowGraphView` (`frontend/src/utils/graphMapper.ts`), and `createWorkflowSelectionGuard` (`frontend/src/utils/workflowSelectionGuard.ts`) are wired into `frontend/src/app/page.tsx` with a workflow selector and `Overview` / `Task DAG` toggle rendered by `WorkflowGraph.tsx` (`RUNNING`, `COMPLETED`, `FAILED`, `RETRYING`, `SKIPPED`, `CANCELLED`). The selection guard isolates the displayed Task DAG and timeline from unrelated WebSocket events and stale out-of-order async fetch responses.
