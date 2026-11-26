# OrynX Production Readiness & Operations Guide

Operational guide for starting, verifying, monitoring, troubleshooting, and safely shutting down the complete OrynX distributed workflow orchestration platform.

> **Scope Notice:** This guide reflects the actual Docker Compose architecture, pinned container versions, health probes, resource bounds, observability stack, and resilience controls validated in local integration and runtime testing. Multi-node cloud/Kubernetes deployments require externalized secrets management, managed HA PostgreSQL/Redis/Kafka, TLS termination, and multi-tenant object storage for Loki and Tempo.

---

## 1. System Architecture & Startup Sequence

OrynX runs across three isolated Docker bridge networks:
- `orynx-edge`: Public ingress (`frontend:3000`, `gateway-service:8080`).
- `orynx-backend`: Internal services and backing stores (`postgres`, `redis`, `kafka`, `auth-service`, `planning-service`, `orchestrator-service`, `execution-service`). No backend service or database publishes host ports by default.
- `orynx-monitoring`: Telemetry plane (`otel-collector`, `tempo`, `loki`, `prometheus`, `alertmanager`, `grafana`, and exporters). All observability UIs bind strictly to `127.0.0.1`.

### Dependency-Ordered Startup
1. **Backing stores**: `postgres:17`, `redis:7`, `apache/kafka:3.7.0` (KRaft mode).
2. **Core backend services**:
   - `planning-service` (`8083`)
   - `auth-service` (`8081`, waits for `postgres` + `kafka`)
   - `execution-service` (`8084`, waits for `postgres` + `kafka`)
   - `orchestrator-service` (`8082`, waits for `postgres` + `kafka` + `redis` + `planning-service`)
3. **Edge services**:
   - `gateway-service` (`8080`, waits for `auth-service` + `orchestrator-service` + `planning-service`)
   - `frontend` (`3000`, waits for `gateway-service`)
4. **Observability profile (`--profile observability`)**:
   - `tempo:2.6.1`, `loki:3.2.1`, `alertmanager:v0.27.0`
   - `otel-collector:0.114.0` (waits for `tempo` + `loki`), `prometheus:v2.55.1` (waits for `alertmanager`)
   - `postgres-exporter`, `redis-exporter`, `kafka-exporter`
   - `grafana:11.3.1` (waits for `prometheus` + `alertmanager` + `tempo` + `loki`)

---

## 2. Environment Configuration & Startup

### Required Environment Variables (`.env`)
Never commit real credentials to Git. Configure `.env` with:
- `POSTGRES_PASSWORD`, `SPRING_DATASOURCE_PASSWORD`
- `POSTGRES_MONITOR_PASSWORD` (required for `--profile observability`)
- `JWT_SECRET` (minimum 32 bytes)
- `NVIDIA_API_KEY` (required by `planning-service` and `orchestrator-service`)
- `GRAFANA_ADMIN_PASSWORD` (required for `--profile observability`)

### Start the Complete Stack
```bash
docker compose --profile observability up -d --build
```

### Verify Service Health & Readiness
```bash
docker compose --profile observability ps
curl -fsS http://localhost:8080/actuator/health/readiness
curl -fsS http://localhost:3000/ >/dev/null && echo "frontend OK"
curl -fsS http://127.0.0.1:9090/-/ready
curl -fsS http://127.0.0.1:3100/ready
curl -fsS http://127.0.0.1:3200/ready
curl -fsS http://127.0.0.1:3001/api/health
```
All 5 Spring Boot services expose only `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` with health details suppressed (`show-details=never`) and enable graceful shutdown (`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`).

### Graceful Shutdown vs Docker Termination Budget
Docker sends `SIGTERM` on `stop`/`restart`/`down`, then `SIGKILL` after `stop_grace_period`. To prevent premature `SIGKILL` before Spring and worker-pool drain complete, `docker-compose.yml` explicitly sets:
- `gateway-service`, `auth-service`, `planning-service`, `orchestrator-service`: `stop_grace_period: 30s` (exceeds Spring's `20s` lifecycle shutdown phase).
- `execution-service`: `stop_grace_period: 45s` (exceeds `orynx.execution.worker.await-termination-seconds=30` plus Spring's `20s` shutdown phase).

> **Graceful Shutdown Limitations:** Graceful shutdown does **not** guarantee completion of arbitrarily long-running workflows. If an active execution exceeds the `30s` worker-pool drain timeout (or the `45s` Docker `stop_grace_period`), the container exits or receives `SIGKILL`; any interrupted execution is recovered after PostgreSQL lease expiry (`300s`) with generation fencing (`StaleWorkerExecutionException`) preventing duplicate or stale writes.

### Safe Shutdown
```bash
# Stop containers while preserving persistent volumes (postgres, redis, kafka, prometheus, loki, tempo, grafana)
docker compose --profile observability down
```
> **DATA LOSS WARNING:** Do **NOT** pass `-v` (`docker compose down -v`) unless you intend to permanently destroy all PostgreSQL workflow data, Kafka topics, Redis AOF state, and telemetry storage.

---

## 3. Container Resource Bounds & JVM Sizing

All services in `docker-compose.yml` declare parameterized `mem_limit` and `cpus` bounds so JVM `-XX:+UseContainerSupport` sizes heaps accurately inside container cgroups. Because `auth-service` and `execution-service` run with OpenTelemetry Java Agent `2.11.0`, Kafka clients, and JDBC/Hikari pools, both default to `768m` with `-XX:MaxRAMPercentage=60.0` (leaving ~40% / ~307 MiB for Metaspace, CodeCache, agent instrumentation, direct buffers, and thread stacks):

| Service | Default `mem_limit` | Default `cpus` | `stop_grace_period` | Default JVM `MaxRAMPercentage` | Override Env Vars |
|---|---:|---:|---:|---:|---|
| `postgres` | `512m` | `1.0` | Docker default | — | `POSTGRES_MEM_LIMIT`, `POSTGRES_CPU_LIMIT` |
| `redis` | `256m` | `0.5` | Docker default | — | `REDIS_MEM_LIMIT`, `REDIS_CPU_LIMIT` |
| `kafka` | `1024m` | `1.0` | Docker default | — | `KAFKA_MEM_LIMIT`, `KAFKA_CPU_LIMIT` |
| `orchestrator-service` | `768m` | `1.0` | `30s` | `75.0%` (`JAVA_OPTS`) | `ORCHESTRATOR_SERVICE_MEM_LIMIT`, `ORCHESTRATOR_SERVICE_CPU_LIMIT` |
| `auth-service` | `768m` | `1.0` | `30s` | `60.0%` (`AUTH_SERVICE_JAVA_OPTS`) | `AUTH_SERVICE_MEM_LIMIT`, `AUTH_SERVICE_JAVA_OPTS` |
| `execution-service` | `768m` | `1.0` | `45s` | `60.0%` (`EXECUTION_SERVICE_JAVA_OPTS`) | `EXECUTION_SERVICE_MEM_LIMIT`, `EXECUTION_SERVICE_JAVA_OPTS` |
| `planning-service` / `gateway-service` | `512m` | `1.0` | `30s` | `75.0%` (`JAVA_OPTS`) | `<SERVICE>_MEM_LIMIT`, `<SERVICE>_CPU_LIMIT` |
| `frontend` | `384m` | `0.5` | Docker default | — | `FRONTEND_MEM_LIMIT`, `FRONTEND_CPU_LIMIT` |
| `prometheus` / `tempo` | `512m` | `1.0` | Docker default | — | `PROMETHEUS_*`, `TEMPO_*` |
| `loki` / `otel-collector` | `384m` | `0.5` | Docker default | — | `LOKI_*`, `OTEL_COLLECTOR_*` |
| `grafana` | `256m` | `0.5` | Docker default | — | `GRAFANA_MEM_LIMIT`, `GRAFANA_CPU_LIMIT` |
| Exporters & Alertmanager | `128m` | `0.25` | Docker default | — | `EXPORTER_*`, `ALERTMANAGER_*` |

### Memory & Prometheus Validation Notes
- Monitor container RSS (`docker stats`) alongside JVM heap (`OrynX Backend Service Health` dashboard). If RSS approaches `mem_limit`, increase `<SERVICE>_MEM_LIMIT` or lower `MaxRAMPercentage` rather than removing limits.
- Prometheus scrapes targets every `15s` (`monitoring/prometheus/prometheus.yml`). When validating counter totals after a workflow batch, wait at least `16–20s` (one full scrape interval) before querying Prometheus so counters reflect the completed batch rather than a mid-batch scrape.
- **Note on `TaskExecutor` Simulator**: In the current local/development runtime, `execution-service`'s `TaskExecutor` uses a built-in stochastic attempt simulator (`Math.random() > 0.4`, `1500ms` delay) to exercise retry and failure paths. Expect stochastic task retries and occasional workflow `FAILED` outcomes during local batch runs.

---

## 4. Workflow Operations & Monitoring

- **API Ingress**: `http://localhost:8080/api/v1/...` (see [API_ENDPOINTS.md](./API_ENDPOINTS.md)).
- **Frontend UI**: `http://localhost:3000` (configured with `NEXT_PUBLIC_GRAFANA_URL=http://localhost:3001` for trace/log deep-links).
- **Grafana**: `http://127.0.0.1:3001` — 8 provisioned dashboards (`Platform Overview`, `Workflow Execution`, `Service Performance`, `Resilience, Workers & Recovery`, `SLO & Operational Reliability`, `Backend Service Health`, `Kafka Messaging`, `Infrastructure`).
- **Prometheus**: `http://127.0.0.1:9090` — scrapes internal `:9100/metrics` across all 5 backend services plus exporters and `otel-collector:8888`.
- **Log Correlation (Loki)**: Query `{service_name=~".*-service"} | workflowId="<id>"` or `| correlationId="<id>"` and click `View Trace in Tempo` on `trace_id`.

---

## 5. Incident Response Runbook Index

| Failure Domain | Runbook |
|---|---|
| Workflow failures & high failure rates | [WorkflowFailureRateHigh.md](./runbooks/WorkflowFailureRateHigh.md), [TaskFailureRateHigh.md](./runbooks/TaskFailureRateHigh.md) |
| Workflow latency & SLO burn | [WorkflowLatencyHigh.md](./runbooks/WorkflowLatencyHigh.md), [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md), [ErrorBudgetExhausted.md](./runbooks/ErrorBudgetExhausted.md) |
| Worker failures, lease expiry, fencing & recovery | [WorkerFailureAndRecovery.md](./runbooks/WorkerFailureAndRecovery.md) |
| Kafka outage, backlog & DLT investigation | [KafkaUnavailable.md](./runbooks/KafkaUnavailable.md), [DeadLetterTopicInvestigation.md](./runbooks/DeadLetterTopicInvestigation.md) |
| Redis outage, locks & admission saturation | [RedisUnavailable.md](./runbooks/RedisUnavailable.md), [RedisCoordinationErrorsHigh.md](./runbooks/RedisCoordinationErrorsHigh.md), [WorkflowAdmissionCapacitySaturated.md](./runbooks/WorkflowAdmissionCapacitySaturated.md) |
| PostgreSQL outage | [PostgreSQLUnavailable.md](./runbooks/PostgreSQLUnavailable.md) |
| Service outage | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |
| Telemetry pipeline, Collector & cardinality | [ObservabilityTelemetryPipelineFailure.md](./runbooks/ObservabilityTelemetryPipelineFailure.md), [ObservabilityCardinalityAndVolume.md](./runbooks/ObservabilityCardinalityAndVolume.md), [ObservabilityTraceInvestigation.md](./runbooks/ObservabilityTraceInvestigation.md) |
| Resilience experiment cleanup failure & quarantine | [ResilienceExperimentCleanupFailed.md](./runbooks/ResilienceExperimentCleanupFailed.md) |
