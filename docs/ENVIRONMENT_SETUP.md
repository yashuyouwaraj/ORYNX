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
- **Consumer Deserialization & Bounded Retries**: Consumers wrap `JsonDeserializer` with `ErrorHandlingDeserializer` and use `DefaultErrorHandler` (`KafkaReliabilityConfig`) with `FixedBackOff` (default `500ms`, `3` retries, configurable via `orynx.kafka.consumer.retry.interval-ms` / `max-attempts`). Non-retryable deserialization/conversion exceptions (`DeserializationException`, `MessageConversionException`, `ConversionException`) skip retries immediately so transient application exceptions (such as `IllegalArgumentException`) remain retryable. When recovery runs, `ConsumerRecordRecoverer` forwards exhausted/poison records to `<topic>.DLT` with metadata and raw/structured payload and synchronously awaits broker confirmation (`future.get` with bounded `orynx.kafka.consumer.dlt.timeout-ms`, default `10000ms`, under `acks=all`) before returning so `setCommitRecovered(true)` only commits the original offset once `.DLT` delivery is durably confirmed; any DLT failure, timeout, or interruption fails recovery closed.
- **Idempotency & Generation Fencing**:
  - `execution-service` protects duplicate execution dispatches via PostgreSQL pessimistic locking (`WorkflowExecutionCoordination` row lock + monotonic fencing `generation`), lease heartbeats, and `ExecutionRecoveryScanner` crash reclaim. On `PAUSED -> RUNNING` resume, the coordination record stays `PAUSED` until the explicit `WorkflowExecutionRequestEvent(resume=true)` reclaims it (preserving protection against pre-resume recovery hijacks), and queue saturation (`WorkerQueueFullException`) releases the lease window without deleting coordination state.
  - `orchestrator-service` initializes `WorkflowExecution.executionGeneration = 1` on start, increments it on both `resumeWorkflow` and `retryWorkflow`, propagates `generation` on `WorkflowExecutionRequestEvent`, and enforces `@Transactional` generation-fenced idempotency in both `ExecutionEventConsumer` and `TaskEventConsumer`: duplicate `eventId` / status updates do not overwrite timestamps, regress terminal task/workflow states, trigger `FAILED -> FAILED` transition exceptions, or conflate retried generations on the task timeline. Events from an older `generation < executionGeneration` are suppressed as stale.
- **Replay & Operational Assumptions**: Replaying historical Kafka offsets safely skips already-terminal workflows, completed tasks, older generations, and duplicate consecutive timeline entries; however, if `workflows`/`workflow_tasks` rows are purged while Kafka topics retain events, unmatched IDs log warnings and are safely ignored. Existing single-partition topics created during development automatically scale to multi-partition clusters without code changes because all keys are already partitioned by `workflowId`/`userId`.

---

## 6. Database Indexing & Bounded Pagination

- **Query-Driven PostgreSQL Indexes**: JPA `@Table(indexes = ...)` declarations and idempotent PostgreSQL advisory-locked startup migrations (`DatabaseIndexMigration` in `orchestrator-service`, `execution-service`, and `auth-service`) provision composite indexes across `workflows` (`user_id, id DESC`, `user_id, status, id DESC`, `status, id DESC`, `scheduled, status, scheduled_at`, `depends_on_workflow_id`), `workflow_tasks` (`workflow_id, execution_order`, `status`), `workflow_executions` (`workflow_id, started_at DESC, id DESC`), `execution_events` (`workflow_id, timestamp DESC, id DESC`, `workflow_id, task_id, id DESC`, `workflow_id, task_name, id DESC`, `workflow_id, event_id`), `workflow_execution_coordination` (`status, lease_expires_at`), and `users` (`email`).
- **Migration Safety & Operational Considerations**:
  - Startup migrations run inside a PostgreSQL transaction under `pg_advisory_xact_lock` (failing startup if lock acquisition or DDL fails on PostgreSQL, and skipping cleanly on non-PostgreSQL datasources).
  - Redundant `idx_workflows_user_id` was removed from `Workflow` and `DatabaseIndexMigration` because `(user_id, id DESC)` (`idx_workflows_user_id_id`) already covers `user_id` equality lookups without extra write amplification. Existing databases retain any previously created `idx_workflows_user_id` non-destructively (operators may optionally run `DROP INDEX CONCURRENTLY IF EXISTS idx_workflows_user_id;` during maintenance).
  - `CREATE INDEX IF NOT EXISTS` inside a startup transaction acquires a PostgreSQL `SHARE` lock during initial index creation on pre-existing tables, briefly blocking concurrent writes on very large tables; for zero-downtime builds on massive existing production tables, create indexes `CONCURRENTLY` ahead of rollout.
- **Bounded Pagination, Sort Allowlists & N+1 Elimination**: `PaginationUtils` enforces default page size `20`, max page size `100`, endpoint-specific sort-field allowlists (rejecting unknown or relation-path sort properties with HTTP `400`), and deterministic `id` tie-breaker sorting across `/api/v1/workflows`, `/api/v1/workflows/status/{status}`, `/api/v1/workflows/{id}/tasks`, `/api/v1/workflows/{id}/executions`, and `/api/v1/timeline/{workflowId}`. `@EntityGraph(attributePaths = "dependsOnWorkflow")` prevents lazy N+1 queries on workflow listings, and dashboard/analytics/performance endpoints use database `GROUP BY` and aggregate queries instead of `findAll()`.
- **Performance & Health Response Bounds**: `GET /api/v1/performance` and `GET /api/v1/performance/health` preserve their `List<...>` response contracts while bounding entity retrieval to `MAX_PAGE_SIZE` (`100`) entries; `getWorkflowHealth()` computes its baseline average from the global database aggregate query across all completed workflows rather than the bounded subset.

---

## 7. Real-Time WebSocket Task Progress (Step 19)

- **Transport & Gateway Routing**: `orchestrator-service` exposes a STOMP-over-SockJS endpoint at `/ws` (`WebSocketConfig`), proxied through `gateway-service` route `orchestrator-websocket` (`Path=/ws/**,/ws`). Allowed browser origins are restricted via `orynx.websocket.allowed-origins` (`WEBSOCKET_ALLOWED_ORIGINS`, defaulting to `http://localhost:3000,http://127.0.0.1:3000`).
- **Authentication & Subscription Authorization**:
  - `JwtAuthenticationFilter` (`gateway-service`) strips any client-supplied `X-User-Id` / `X-User-Email` headers on `/ws` and `/ws/**`.
  - `WebSocketAuthChannelInterceptor` (`orchestrator-service`) validates the `Authorization: Bearer <jwt>` native STOMP header on `CONNECT` using `OrchestratorJwtService`, binding a `WebSocketPrincipal` keyed by `userId`.
  - On `SUBSCRIBE` and `SEND`, expired or unauthenticated sessions are rejected. Global broadcast subscriptions (`/topic/workflows`, `/topic/tasks`) are blocked. Clients may subscribe only to their own user queues (`/user/queue/workflows`, `/user/queue/tasks`) or to `/topic/workflows/{workflowId}` and `/topic/workflows/{workflowId}/tasks` after verifying `workflowRepository.findByIdAndUserId(workflowId, userId)`.
- **Event Contracts & Reliability**:
  - `WorkflowExecutionEvent`: `{ workflowId, workflowName, status, generation, timestamp, eventId }` (published on workflow create, start, cancel, pause, resume, retry, and completion/failure).
  - `TaskExecutionEvent`: `{ workflowId, taskId, taskName, status, generation, eventId, timestamp }` (published after Kafka deduplication and generation-fenced persistence in `TaskEventConsumer`).
  - WebSocket delivery is a **supplementary** real-time channel; PostgreSQL remains the source of truth. The frontend (`frontend/src/services/websocket.ts`) deduplicates by `eventId`, drops stale `generation` events, cleans up subscriptions on unmount, and re-fetches authoritative REST state on reconnect.
- **Known Limitations**:
  - In-memory simple STOMP broker (`/topic`, `/queue`) is single-instance; horizontal multi-replica `orchestrator-service` deployments would require an external STOMP relay (e.g. RabbitMQ) or Redis pub/sub fan-out.
  - Because WebSocket messages are sent after Kafka consumer/service processing without a transactional outbox, a process crash between DB commit and WebSocket send is recovered by client REST re-sync on reconnect.

---

## 8. Containerization & Deployment Architecture (Step 21)

- **Multi-Stage Non-Root Images**:
  - All 5 Spring Boot services (`gateway-service`, `auth-service`, `orchestrator-service`, `planning-service`, `execution-service`) use multi-stage Maven + `eclipse-temurin:21-jre-alpine` Dockerfiles with `.dockerignore`, layer-cached `dependency:go-offline`, non-root `orynx` user, container-aware `JAVA_OPTS` (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`), `exec java` for direct `SIGTERM` graceful shutdown, and `/actuator/health/readiness` `HEALTHCHECK` probes.
  - `frontend` uses Next.js `output: "standalone"` (`frontend/next.config.ts`) in a 3-stage `node:22-alpine` Dockerfile running as non-root `nextjs` on port `3000`.
- **Network Boundaries & Port Exposure (`docker-compose.yml`)**:
  - `orynx-edge`: `frontend` (`3000:3000`) and `gateway-service` (`8080:8080`) are the only host-published entry points.
  - `orynx-backend`: internal network hosting `auth-service` (`8081`), `orchestrator-service` (`8082`), `planning-service` (`8083`), `execution-service` (`8084`), `postgres` (`5432`), `kafka` (`9092`), and `redis` (`6379`) via `expose` only (no host-published ports by default).
  - Persistent named volumes: `orynx_postgres_data`, `orynx_kafka_data`, `orynx_redis_data`.
  - Readiness-gated `depends_on` (`condition: service_healthy`) prevents premature startup failures.
- **Full-Stack Container Startup & Operations**:
  - Copy `.env.example` to `.env` and populate all mandatory secrets (`POSTGRES_PASSWORD`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `NVIDIA_API_KEY`). Compose fails fast if mandatory variables are missing.
  - Validate & build: `docker compose config -q && docker compose build`
  - Start full stack: `docker compose up -d`
  - Inspect health & logs: `docker compose ps` and `docker compose logs -f <service>`
  - Restart a service: `docker compose restart <service>`
  - Stop without deleting volumes: `docker compose down`
- **Local Host-Based Development Override (`docker-compose.dev.yml`)**:
  - To run backend services via `mvnw.cmd` on the host while keeping Postgres/Redis/Kafka in containers on loopback `127.0.0.1`, run:
    `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis kafka`
- **Production Limitations**:
  - Docker Compose is intended for reproducible single-host/staging deployments, not full production secrets management or multi-node orchestration; production environments should inject secrets via a dedicated vault/KMS and add TLS termination at an ingress/reverse proxy.

---

## 9. Observability & Monitoring Stack (Step 22 / 22.1)

- **Profile-Gated Observability (`docker-compose.yml`)**:
  - The core platform (`docker compose up -d`) and dev override (`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis kafka`) run without requiring `GRAFANA_ADMIN_PASSWORD` or `POSTGRES_MONITOR_PASSWORD`.
  - Start the core platform plus Prometheus, Grafana, Tempo, OpenTelemetry Collector, and Postgres/Redis/Kafka exporters with:
    `docker compose --profile observability up -d --wait`
    (Requires `GRAFANA_ADMIN_PASSWORD` and `POSTGRES_MONITOR_PASSWORD` in `.env`; `grafana` and `postgres-exporter` fail fast on startup if missing.)
  - Monitoring endpoints bind exclusively to host loopback (`127.0.0.1`): Grafana on `http://127.0.0.1:3001`, Prometheus on `http://127.0.0.1:9090`, and Tempo on `http://127.0.0.1:3200`.
  - Backend services expose Prometheus metrics on port `9100` (`/metrics`) bound strictly to their `orynx-monitoring` (`172.29.22.0/24`) interface IP (unreachable from `frontend` on `orynx-edge`), preserving Actuator `health`-only boundaries on ports `8080`–`8084`.
  - `postgres-exporter` authenticates using the least-privilege `orynx_monitor` role (`pg_monitor`, `NOSUPERUSER`) provisioned via `monitoring/postgres/ensure-monitoring-user.sh`.
  - OpenTelemetry Java Agent (`v2.11.0`, SHA-256 verified at image build time) is controlled via `OTEL_JAVAAGENT_ENABLED` (default `true`) and `OTEL_TRACES_SAMPLER_ARG` (default `1.0`).
  - See [docs/OBSERVABILITY.md](./OBSERVABILITY.md) for full architecture, metrics, tracing, structured logging, healthchecks, resource limits, and dashboard documentation.

---

## 10. Security Guardrails
- **NEVER** commit `.env` or any file containing real passwords or keys to Git.
- Root `.gitignore` automatically ignores `.env`, `.env.*`, and `*.env`.
- Any missing required variable will cause services to fail fast on startup with an explicit placeholder resolution error.
