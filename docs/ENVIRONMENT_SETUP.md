# OrynX Local Environment Setup Guide

This guide describes how to configure environment variables required to run OrynX microservices locally or in containerized environments.

---

## 1. Required Environment Variables

| Variable Name | Required By | Description | Example / Recommendation |
| :--- | :--- | :--- | :--- |
| `SPRING_DATASOURCE_USERNAME` | `auth-service`, `orchestrator-service`, `execution-service` | PostgreSQL database username | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | `auth-service`, `orchestrator-service`, `execution-service` | PostgreSQL database user password | *(Strong local password)* |
| `JWT_SECRET` | `auth-service` | Secret key for signing HS-256 JWT tokens (min 32 bytes / 256 bits) | *(At least 32 characters)* |
| `JWT_EXPIRATION` | `auth-service` | Token expiration in milliseconds (optional, defaults to `3600000` = 1 hour) | `3600000` |
| `NVIDIA_API_KEY` | `planning-service`, `orchestrator-service` | API key for NVIDIA NIM LLM inference endpoints | `nvapi-...` |
| `POSTGRES_PASSWORD` | Docker Compose (`postgres`) | Password initialized for PostgreSQL root container | Same as `SPRING_DATASOURCE_PASSWORD` |

---

## 2. Setting Environment Variables

### Windows PowerShell
To set environment variables in your current PowerShell session:
```powershell
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "your_secure_password"
$env:JWT_SECRET = "your-at-least-32-chars-long-secure-random-jwt-secret-key!"
$env:JWT_EXPIRATION = "3600000"
$env:NVIDIA_API_KEY = "your_nvidia_api_key"
```

To persist permanently in your Windows user profile:
```powershell
[System.Environment]::SetEnvironmentVariable("SPRING_DATASOURCE_USERNAME", "postgres", "User")
[System.Environment]::SetEnvironmentVariable("SPRING_DATASOURCE_PASSWORD", "your_secure_password", "User")
[System.Environment]::SetEnvironmentVariable("JWT_SECRET", "your-at-least-32-chars-long-secure-random-jwt-secret-key!", "User")
[System.Environment]::SetEnvironmentVariable("NVIDIA_API_KEY", "your_nvidia_api_key", "User")
```

### Linux / macOS (bash / zsh)
To set environment variables in your current terminal session:
```bash
export SPRING_DATASOURCE_USERNAME="postgres"
export SPRING_DATASOURCE_PASSWORD="your_secure_password"
export JWT_SECRET="your-at-least-32-chars-long-secure-random-jwt-secret-key!"
export JWT_EXPIRATION="3600000"
export NVIDIA_API_KEY="your_nvidia_api_key"
```

To persist permanently, add the export statements above to your `~/.bashrc` or `~/.zshrc`.

---

## 3. Using Docker Compose
1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Edit `.env` with your actual development credentials.
3. Run containers:
   ```bash
   docker compose up -d
   ```
   Docker Compose automatically loads `.env` and injects variables into service definitions.

---

## 4. Network Boundary & Actuator Hardening Defaults

- **Container Interface Binding vs. Host Port Publishing**: Backend services (`8081`–`8084`) bind their default container interfaces (`0.0.0.0` inside containers) so container-to-container traffic over internal Docker networks (configured via `AUTH_SERVICE_URL`, `ORCHESTRATOR_SERVICE_URL`, and `PLANNING_SERVICE_URL`, e.g. `http://auth-service:8081`) functions reliably. Host access is controlled strictly at the port-publishing boundary: `docker-compose.yml` binds PostgreSQL, Redis, and Kafka host ports exclusively to `127.0.0.1`, and containerized backend services should use `expose` (or `127.0.0.1:<port>:<port>` only when host loopback debugging is needed). Only `gateway-service` (`8080`) should publish external ingress.
- **Actuator Immutability**: All five services enforce `include=health`, `discovery.enabled=false`, and `show-details=never` / `show-components=never` (including health probe groups) both via `application.properties` and runtime `ActuatorSecurityEnforcer` / path-normalizing boundary filters, preventing accidental `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=*` deployment overrides.

---

## 5. Kafka Event Reliability Architecture

### 5.1 Topics, Producers & Consumer Groups

| Topic | Producer | Consumer (Group ID) | Message Key | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `user-registered` | `auth-service` (`KafkaProducer`) | `auth-service` (`orynx-auth-group`) | `userId` (fallback: `email`) | User registration domain event |
| `workflow-created` | `orchestrator-service` (`KafkaProducer`) | `orchestrator-service` (`orynx-orchestrator-group`) | `workflowId` | Workflow creation notification |
| `workflow-events` | `orchestrator-service` (`WorkflowEventProducer`) | `orchestrator-service` (`orynx-workflow-group`) | `workflowId` | WebSocket real-time workflow status updates |
| `task-execution-events` | `orchestrator-service` (`TaskEventProducer`) | `orchestrator-service` (`orynx-task-group`) | `workflowId` (fallback: `taskId`) | Internal orchestrator timeline & task status stream |
| `workflow-execution` | `orchestrator-service` (`ExecutionRequestProducer`) | `execution-service` (`execution-service`) | `workflowId` | Start, retry, and resume execution dispatch requests (`eventId`, `executionId`, `resume`) |
| `workflow-cancelled` | `orchestrator-service` (`WorkflowCancellationProducer`) | `execution-service` (`execution-service`) | `workflowId` | Distributed workflow cancellation signal |
| `workflow-paused` | `orchestrator-service` (`WorkflowPauseProducer`) | `execution-service` (`execution-service`) | `workflowId` | Distributed workflow pause signal |
| `workflow-resumed` | `orchestrator-service` (`WorkflowResumeProducer`) | `execution-service` (`execution-service`) | `workflowId` | Distributed pause-clearance signal |
| `task-started` | `execution-service` (`ExecutionEventProducer`) | `orchestrator-service` (`orchestrator-service`) | `workflowId` | Task execution started (`generation`, `eventId`) |
| `task-completed` | `execution-service` (`ExecutionEventProducer`) | `orchestrator-service` (`orchestrator-service`) | `workflowId` | Task completion/failure (`generation`, `eventId`) |
| `workflow-completed` | `execution-service` (`ExecutionEventProducer`) | `orchestrator-service` (`orchestrator-service`) | `workflowId` | Terminal workflow completion/failure (`generation`, `eventId`) |
| `workflow-execution-paused` | `execution-service` (`ExecutionEventProducer`) | `orchestrator-service` (`orchestrator-service`) | `workflowId` | Execution pause checkpoint report (`generation`, `eventId`) |

### 5.2 Partitioning, Keys & Ordering Guarantees

- **Partition Affinity**: Every workflow and task topic uses `String.valueOf(workflowId)` as its Kafka record key; `user-registered` uses `String.valueOf(userId)`. With Kafka's default murmur2 partitioner, all records sharing a `workflowId` on a given topic deterministically route to the same partition regardless of partition count, while distinct workflows distribute horizontally across partitions.
- **Guaranteed Ordering**: Strict FIFO delivery order holds **per-topic, per-partition, per-`workflowId`** when events are produced synchronously/sequentially from a single lease-owning worker or orchestrator transaction.
- **Explicit Non-Guarantees**: Kafka does **not** guarantee global ordering across different topics (for example, `workflow-resumed` vs. `workflow-execution`, or `workflow-cancelled` vs. `workflow-completed`). Both services handle cross-topic races explicitly via database state guards (`WorkflowStateValidator`, `ExecutionCoordinationService`), dual-path pause clearance on resume (`WorkflowResumedEvent` and `WorkflowExecutionRequestEvent.resume=true`), and generation fencing.

### 5.3 Producer & Consumer Reliability, Idempotency & Recovery

- **Producer Hardening**: All three Kafka services (`auth-service`, `orchestrator-service`, `execution-service`) configure `acks=all`, `enable.idempotence=true`, `retries=3`, `max.in.flight.requests.per.connection=5`, `request.timeout.ms=10000`, `delivery.timeout.ms=30000`, and `retry.backoff.ms=500`, with asynchronous send-completion callbacks logging partition/offset metadata and delivery failures.
- **Consumer Deserialization & Bounded Retries**: Consumers wrap `JsonDeserializer` with `ErrorHandlingDeserializer` and use `DefaultErrorHandler` (`KafkaReliabilityConfig`) with `FixedBackOff` (default `500ms`, `3` retries, configurable via `orynx.kafka.consumer.retry.interval-ms` / `max-attempts`). Non-retryable deserialization/conversion exceptions (`DeserializationException`, `MessageConversionException`, `ConversionException`) skip retries immediately so transient application exceptions (such as `IllegalArgumentException`) remain retryable, while exhausted/poison records are logged with metadata, forwarded to `<topic>.DLT` with raw payload/context for inspection and replay when a `KafkaTemplate` is wired, and offset-committed (`setCommitRecovered(true)`) so a single corrupt record cannot stall partition consumption.
- **Idempotency & Generation Fencing**:
  - `execution-service` protects duplicate execution dispatches via PostgreSQL pessimistic locking (`WorkflowExecutionCoordination` row lock + monotonic fencing `generation`), lease heartbeats, and `ExecutionRecoveryScanner` crash reclaim. On `PAUSED -> RUNNING` resume, the coordination record stays `PAUSED` until the explicit `WorkflowExecutionRequestEvent(resume=true)` reclaims it (preserving protection against pre-resume recovery hijacks), and queue saturation (`WorkerQueueFullException`) releases the lease window without deleting coordination state.
  - `orchestrator-service` initializes `WorkflowExecution.executionGeneration = 1` on start, increments it on both `resumeWorkflow` and `retryWorkflow`, propagates `generation` on `WorkflowExecutionRequestEvent`, and enforces `@Transactional` generation-fenced idempotency in both `ExecutionEventConsumer` and `TaskEventConsumer`: duplicate `eventId` / status updates do not overwrite timestamps, regress terminal task/workflow states, trigger `FAILED -> FAILED` transition exceptions, or conflate retried generations on the task timeline. Events from an older `generation < executionGeneration` are suppressed as stale.
- **Replay & Operational Assumptions**: Replaying historical Kafka offsets safely skips already-terminal workflows, completed tasks, older generations, and duplicate consecutive timeline entries; however, if `workflows`/`workflow_tasks` rows are purged while Kafka topics retain events, unmatched IDs log warnings and are safely ignored. Existing single-partition topics created during development automatically scale to multi-partition clusters without code changes because all keys are already partitioned by `workflowId`/`userId`.

---

## 6. Security Guardrails
- **NEVER** commit `.env` or any file containing real passwords or keys to Git.
- Root `.gitignore` automatically ignores `.env`, `.env.*`, and `*.env`.
- Any missing required variable will cause services to fail fast on startup with an explicit placeholder resolution error.
