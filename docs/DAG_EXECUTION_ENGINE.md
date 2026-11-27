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

### Kahn's Algorithm Validation Bounds (`DagValidator`)
Rejected with `HTTP 400` prior to persistence:
- Cycles, self-dependencies, duplicate `taskKey`s, nonexistent dependencies
- Maximum nodes: `100`
- Maximum dependency edges: `500`
- Maximum graph depth: `32`
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
- **Concurrency Limits (`application.properties`)**:
  - `orynx.execution.dag.core-pool-size=8`
  - `orynx.execution.dag.max-pool-size=16`
  - `orynx.execution.dag.queue-capacity=100`
  - `orynx.execution.dag.max-per-workflow-concurrency=4`
- **Per-Task Timeout**: Each attempt is guarded by a daemon timeout watcher interrupting hung tasks at `timeoutMs` and clearing thread interrupt state before pool reuse.
- **Durable Checkpoints (`workflow_task_checkpoints`)**:
  - Unique key `(workflow_id, task_key)` with `PESSIMISTIC_WRITE` locking in `TaskCheckpointService`.
  - **Generation Fencing**: Stale generations (`effectiveGen < existing.executionGeneration`) fail with `StaleWorkerExecutionException`.
  - **Idempotency**: `COMPLETED` checkpoints are never overwritten or regressed.
- **Crash Recovery**: On lease-expiry recovery (`generation > 1`) or resume, `DagExecutionCoordinator` loads durable checkpoints, restores `outputJson` from `COMPLETED` tasks, and schedules **only the incomplete DAG frontier**.
- **Failure Propagation**: When a branch task exhausts retries, transitive downstream dependents are deterministically marked `SKIPPED` (persisted in checkpoints and emitted via `TaskCompletedEvent(skipped=true)`).

---

## 5. Observability & Frontend DAG Rendering
- **Tracing**: Parallel task spans (`orynx.task.execute`) inherit the parent workflow trace context and record `orynx.task.key`, `orynx.task.type`, `orynx.task.attempt`, and `orynx.execution.generation`.
- **Bounded Metrics (`ExecutionMetrics`)**: `orynx_dag_tasks_total{task_type, outcome}`, `orynx_dag_task_duration_seconds{task_type, outcome}`, `orynx_dag_active_tasks`, `orynx_dag_checkpoint_duration_seconds`, `orynx_dag_recoveries_total`, `orynx_dag_task_timeouts_total`.
- **Frontend**: `mapWorkflowTasksToDagGraph` (`frontend/src/utils/graphMapper.ts`) computes topological depth layers and multi-edge fan-out/fan-in connections rendered by `WorkflowGraph.tsx` with `RUNNING`, `COMPLETED`, `FAILED`, `RETRYING`, `SKIPPED`, and `CANCELLED` states.
