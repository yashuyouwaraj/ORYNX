# OrynX Production Observability, SLOs & Alerting (Step 22 / 22.1 / 23 / 26)

> **Step 26 Extension**: For distributed tracing across Kafka & async workers, centralized Loki log aggregation, trace-to-log-to-metric correlation, correlation sanitization, and Step 26 dashboards/runbooks, see [docs/DISTRIBUTED_OBSERVABILITY.md](./DISTRIBUTED_OBSERVABILITY.md).

## 1. Architecture Overview

OrynX implements a hardened observability and operational reliability stack using **Prometheus** (metrics, SLO recording rules, and alert evaluation), **Alertmanager** (alert routing, grouping, deduplication, and inhibition), **OpenTelemetry Java Agent + Collector + Grafana Tempo** (distributed tracing), **Logstash Logback Encoder** (structured JSON logging), **Infrastructure Exporters** (PostgreSQL, Redis, Kafka), and **Grafana** (unified dashboards, SLO error budgets, and trace exploration).

```text
+-------------------------------------------------------------------------+
| orynx-edge                                                              |
|   frontend (:3000)  --->  gateway-service (:8080)                       |
+-----------------------------------+-------------------------------------+
                                    |
+-----------------------------------v-------------------------------------+
| orynx-backend                                                           |
|   gateway-service  auth-service  orchestrator-service  planning-service |
|   execution-service                                                     |
|   postgres (:5432)  redis (:6379)  kafka (:9092)                        |
+-------------------+-----------------------------------------------------+
                    | (metrics bound strictly to 172.29.22.x:9100, OTLP :4317)
+-------------------v-----------------------------------------------------+
| orynx-monitoring (172.29.22.0/24)                                       |
|   prometheus (127.0.0.1:9090 -> :9090) ---> alertmanager (127.0.0.1:9093)|
|   otel-collector (:4317/:4318/:13133) ---> tempo (127.0.0.1:3200)       |
|   postgres-exporter (:9187) | redis-exporter (:9121) | kafka-exporter   |
|   grafana (127.0.0.1:3001 -> :3000)                                     |
+-------------------------------------------------------------------------+
```

### Security & Network Boundary Guarantees
- **Actuator Boundary Preservation**: Per Step 16/16.1 security controls, Spring Boot Actuator HTTP exposure on application ports (`8080`–`8084`) remains strictly locked to `health` (`ActuatorSecurityEnforcer` & `ActuatorBoundaryFilter`).
- **Interface-Isolated Metrics Endpoint (`:9100/metrics`)**: Each Spring Boot service runs `PrometheusMetricsServer` on port `9100` (`orynx.observability.metrics.enabled=true`). In Docker Compose, `orynx-monitoring` uses subnet `${ORYNX_MONITORING_SUBNET:-172.29.22.0/24}` and each service sets `ORYNX_OBSERVABILITY_METRICS_NETWORK_PREFIX=172.29.22.`, causing `PrometheusMetricsServer` to bind **exclusively** to the container's `172.29.22.x` (`orynx-monitoring`) network interface IP (and `127.0.0.1` in local/non-Docker runs) rather than wildcard `0.0.0.0`. Consequently, `frontend` on `orynx-edge` cannot reach `gateway-service:9100/metrics` (`Connection refused`).
- **Loopback-Restricted Host UIs**: Prometheus (`127.0.0.1:9090:9090`), Alertmanager (`127.0.0.1:9093:9093`), Tempo (`127.0.0.1:3200:3200`), and Grafana (`127.0.0.1:3001:3000`) bind exclusively to host loopback (`127.0.0.1`) rather than `0.0.0.0`, preventing unauthenticated LAN exposure.

---

## 2. Observability Services, Ports, Healthchecks & Resource Limits

| Component | Image | Container Port | Host Binding | Healthcheck | Memory / CPU Limit |
|---|---|---:|---|---|---|
| Prometheus | `prom/prometheus:v2.55.1` | `9090` | `127.0.0.1:9090` | `http://127.0.0.1:9090/-/healthy` | `512m` / `1.0` |
| Alertmanager | `prom/alertmanager:v0.27.0` | `9093` | `127.0.0.1:9093` | `http://127.0.0.1:9093/-/healthy` | `128m` / `0.25` |
| Grafana | `grafana/grafana:11.3.1` | `3000` | `127.0.0.1:3001` | `http://127.0.0.1:3000/api/health` | `256m` / `0.5` |
| Grafana Tempo | `grafana/tempo:2.6.1` | `3200`, `4317`, `4318` | `127.0.0.1:3200` | `http://127.0.0.1:3200/ready` | `512m` / `1.0` |
| OTel Collector | `orynx-otel-collector:0.114.0` (`otel/opentelemetry-collector-contrib:0.114.0`) | `4317`, `4318`, `8888`, `13133` | Internal only | `http://127.0.0.1:13133/` | `384m` / `0.5` |
| Postgres Exporter | `prometheuscommunity/postgres-exporter:v0.16.0` | `9187` | Internal only | `http://127.0.0.1:9187/metrics` | `128m` / `0.25` |
| Redis Exporter | `oliver006/redis_exporter:v1.66.0-alpine` | `9121` | Internal only | `http://127.0.0.1:9121/health` | `128m` / `0.25` |
| Kafka Exporter | `danielqsj/kafka-exporter:v1.8.0` | `9308` | Internal only | `http://127.0.0.1:9308/metrics` | `128m` / `0.25` |
| Backend Metrics | Spring Boot `PrometheusMetricsServer` | `9100` | Internal (`172.29.22.x:9100`) | Scraped by Prometheus | Container JVM limit |

All monitoring resource limits and retention settings are configurable via `.env` (`PROMETHEUS_MEM_LIMIT`, `PROMETHEUS_RETENTION_TIME=35d`, `PROMETHEUS_RETENTION_SIZE=10GB`, `ALERTMANAGER_MEM_LIMIT`, `GRAFANA_MEM_LIMIT`, `TEMPO_MEM_LIMIT`, `OTEL_COLLECTOR_MEM_LIMIT`, `EXPORTER_MEM_LIMIT`, and corresponding `*_CPU_LIMIT` variables).

---

## 3. Metrics Collection & Custom Workflow Metrics

### Standard Micrometer & JVM Metrics
All 5 Spring Boot services expose:
- `http_server_requests_seconds_*` (with percentile histograms enabled via `management.metrics.distribution.percentiles-histogram.http.server.requests=true`)
- `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`, `jvm_threads_live_threads`, `process_cpu_usage`
- Common tag: `application=${spring.application.name}`

### Custom Business Metrics (`orchestrator-service`)
`WorkflowMetrics` (`com.orynx.orchestrator.common.metrics.WorkflowMetrics`) pre-registers all bounded series at application startup so initial scrapes and dashboard queries have an immediate zero baseline:
- `orynx_workflows_total` (Micrometer `orynx.workflows.created`) — Counter incremented when a workflow is created via either standard REST (`WorkflowService.createWorkflow`) or AI generation (`AiWorkflowService.createdAiWorkflow`).
- `orynx_workflows_started_total` — Counter incremented when a workflow execution starts (`startWorkflow` and `retryWorkflow`).
- `orynx_workflows_retried_total` — Counter incremented when a failed workflow is retried (`retryWorkflow`).
- `orynx_workflows_cancelled_total` — Counter incremented when a workflow transitions to `CANCELLED` (`cancelWorkflow`; idempotent repeat calls do not double-count).
- `orynx_workflows_completed_total{status}` — Counter pre-registered for `status` in `COMPLETED`, `FAILED`, `CANCELLED`. Guarded against duplicate Kafka events and stale/fenced execution generations.
- `orynx_workflows_duration_seconds{status}` — Percentile-histogram `Timer` (`publishPercentileHistogram=true`) pre-registered for `status` in `COMPLETED`, `FAILED`. Measures the duration of the specific `WorkflowExecution` attempt (`execution.getDurationMs()`), never reusing stale pre-retry `startedAt` timestamps.
- `orynx_tasks_executed_total{status}` — Counter pre-registered for `status` in `COMPLETED`, `FAILED`. Recorded in `ExecutionEventConsumer.consumeTaskCompleted` upon actual distributed task state transitions from `execution-service`, suppressing duplicate deliveries and fenced/stale generations.

**Cardinality Guardrail**: Unbounded identifiers (`userId`, `workflowId`, `taskId`, `executionId`, `requestId`) are never used as Prometheus metric labels.

---

## 4. Distributed Tracing & Sampling (OpenTelemetry + Tempo)

- **SHA-256 Verified Agent Packaging**: Each backend `Dockerfile` downloads `opentelemetry-javaagent-2.11.0.jar` and verifies its SHA-256 digest (`4cff4ab46179260a61fc0d884f3f170cfbd9d2962dd260be2cff31262d0c7618`) via `sha256sum -c -`, failing the image build if verification fails.
- **Enable/Disable & Collector-Down Resilience**: Controlled via `OTEL_JAVAAGENT_ENABLED` (default `true`; set `false` to disable without rebuilding). When enabled, `-Dotel.javaagent.logging=none` prevents unstructured agent stderr spam when the `observability` profile is inactive or `otel-collector` is unreachable, while core workflow execution proceeds unaffected.
- **Noise Suppression & Sampling**:
  - `OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false` suppresses recurring idle `@Scheduled` cron polling traces.
  - `OTEL_INSTRUMENTATION_HIBERNATE_ENABLED=false` and `OTEL_INSTRUMENTATION_SPRING_DATA_ENABLED=false` suppress redundant ORM wrapper spans while retaining JDBC SQL, HTTP, and Kafka spans.
  - `otel-collector` (`monitoring/otel/otel-collector-config.yaml`) applies the `filter/healthchecks` processor to drop `/actuator/health.*` readiness/liveness spans before export to Tempo.
  - Sampling ratio is controlled via `OTEL_TRACES_SAMPLER=parentbased_traceidratio` and `OTEL_TRACES_SAMPLER_ARG` (default `1.0` for local/staging visibility; tune e.g. to `0.1`–`0.25` in high-volume production).

---

## 5. Structured Logging & Correlation

- **`docker` Spring Profile (`SPRING_PROFILES_ACTIVE=docker`)**: All 5 services emit single-line JSON logs via `LogstashEncoder` (`logback-spring.xml`) containing `@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `service` (without duplicate `SERVICE_NAME` properties), and MDC correlation fields (`trace_id`, `span_id`, `requestId`, `workflowId`).
- **Raw SQL Stdout Disabled**: `spring.jpa.show-sql` defaults to `false` (`${SPRING_JPA_SHOW_SQL:false}`), preventing multi-line non-JSON SQL dumps on container stdout.
- **MDC Correlation**:
  - `requestId` is populated from the `X-Request-ID` header across `gateway-service`, `auth-service`, `orchestrator-service`, `planning-service`, and `execution-service` (never inventing fake IDs when absent).
  - `workflowId` is populated in `orchestrator-service` (HTTP workflow/timeline requests and Kafka execution/task consumers) and `execution-service` (`ExecutionRequestConsumer` and `WorkflowExecutionEngine`).
- **Privacy Guardrail**: Passwords, JWT tokens, API keys, and raw request payloads are never logged.

---

## 6. Least-Privilege Infrastructure Exporters

- **PostgreSQL (`postgres-exporter`)**: Connects using the dedicated least-privilege monitoring role `${POSTGRES_MONITOR_USER:-orynx_monitor}` (`NOSUPERUSER NOCREATEDB NOCREATEROLE`, granted `pg_monitor`), provisioned idempotently by `monitoring/postgres/ensure-monitoring-user.sh` when `POSTGRES_MONITOR_PASSWORD` is configured. Primary superuser credentials (`POSTGRES_PASSWORD`) are never passed to `postgres-exporter`.
- **Redis (`redis-exporter`)**: `oliver006/redis_exporter:v1.66.0-alpine` scrapes `redis://redis:6379` on `orynx-backend`.
- **Kafka (`kafka-exporter`)**: `danielqsj/kafka-exporter:v1.8.0` exports broker, partition offset, and consumer group lag metrics (`kafka_consumergroup_lag`).

---

## 7. Provisioned Grafana Dashboards & SLO / Alerting Integration

Grafana (`http://127.0.0.1:3001`) provisions **Prometheus** (default), **Tempo**, and **Alertmanager** datasources and 6 dashboards under folder **OrynX**:
1. **OrynX Platform Overview** (`orynx-platform-overview.json`): Backend services up, application HTTP RPS, 5xx error rate, and P95 latency (excluding `/actuator/health.*` probe traffic), plus workflows started.
2. **OrynX Backend Service Health** (`orynx-backend-service-health.json`): JVM heap memory, CPU usage, live threads, and GC pause rate across all 5 services.
3. **OrynX Workflow Execution** (`orynx-workflow-execution.json`): Workflows created (standard + AI), started, completed, failed, cancelled, and retried, plus distributed tasks executed by status (`orynx_tasks_executed_total`) and workflow execution duration average/max (`orynx_workflows_duration_seconds`).
4. **OrynX Kafka Messaging** (`orynx-kafka-messaging.json`): Brokers, consumer group lag (`kafka_consumergroup_lag`), topic count, and message rate.
5. **OrynX Infrastructure** (`orynx-infrastructure.json`): PostgreSQL, Redis, and OTel Collector health (`up{job="otel-collector"}`), exported spans (`otelcol_exporter_sent_spans*`), span ingestion vs export rates, active connections, committed transactions, and Redis memory.
6. **OrynX SLO & Operational Reliability** (`orynx-slo-reliability.json`): API availability SLI (`99.9%` target), Workflow completion reliability SLI (`99.0%` target), Task execution reliability SLI (`99.0%` target), 30-day Error Budget Remaining gauges, Multi-Window Fast/Slow Burn Rates, Latency Percentiles vs SLO targets, Active Firing/Pending Alerts, Infrastructure Health, and **Distributed Coordination & Admission Control** (active admitted workflows, capacity saturation gauge, and rejection breakdown).

See [docs/SLO.md](./SLO.md) for the complete SLI/SLO and error budget specification, [docs/ALERTING.md](./ALERTING.md) for the Prometheus alert catalog and Alertmanager routing/inhibition architecture, [docs/REDIS_COORDINATION.md](./REDIS_COORDINATION.md) for Redis distributed coordination and admission control architecture, and [docs/runbooks/](./runbooks/) for incident response procedures.

---

## 8. Operations & Verification

### Running the Core Stack Without Observability
`GRAFANA_ADMIN_PASSWORD` and `POSTGRES_MONITOR_PASSWORD` are **not** required when running the base stack or dev override without `--profile observability`:
```bash
docker compose config -q
docker compose up -d
```

### Running the Full Stack With Observability
1. Set `GRAFANA_ADMIN_PASSWORD` and `POSTGRES_MONITOR_PASSWORD` in `.env` (if omitted, `grafana` and `postgres-exporter` fail fast with an explicit error on startup). Optionally set `ALERTMANAGER_WEBHOOK_URL` to forward alerts to an external webhook receiver.
2. Validate, build, and start:
   ```bash
   docker compose --profile observability config -q
   docker compose --profile observability build
   docker compose --profile observability up -d --wait
   ```
3. Access local UIs (bound to `127.0.0.1` only):
   - **Grafana**: `http://127.0.0.1:3001` (`GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`)
   - **Prometheus**: `http://127.0.0.1:9090/targets` and `http://127.0.0.1:9090/alerts`
   - **Alertmanager**: `http://127.0.0.1:9093`
   - **Tempo API**: `http://127.0.0.1:3200/api/search`
