# OrynX Production Observability & Infrastructure Monitoring (Step 22)

## 1. Architecture Overview

OrynX implements a production-oriented observability stack using **Prometheus** (metrics), **OpenTelemetry Java Agent + Collector + Grafana Tempo** (distributed tracing), **Logstash Logback Encoder** (structured JSON logging), **Infrastructure Exporters** (PostgreSQL, Redis, Kafka), and **Grafana** (unified dashboards & trace exploration).

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
                    | (metrics :9100/metrics, OTLP :4317)
+-------------------v-----------------------------------------------------+
| orynx-monitoring                                                        |
|   prometheus (:9090) <--- scrapes :9100/metrics + exporters             |
|   otel-collector (:4317/:4318) ---> tempo (:3200)                       |
|   postgres-exporter (:9187) | redis-exporter (:9121) | kafka-exporter   |
|   grafana (:3001 -> :3000)                                              |
+-------------------------------------------------------------------------+
```

### Security & Actuator Boundary Preservation
Per Step 16/16.1 security controls, Spring Boot Actuator HTTP exposure on service ports (`8080`–`8084`) remains strictly locked to `health` (`ActuatorSecurityEnforcer`).
Instead of exposing `/actuator/prometheus` on the application HTTP port, each Spring Boot service runs `PrometheusMetricsServer` on an internal-only management port (`9100`) bound to the `orynx-monitoring` network (`orynx.observability.metrics.enabled=true`). Only `GET /metrics` is served on port `9100`.

---

## 2. Observability Services & Ports

| Component | Image | Container Port | Host Port | Network(s) |
|---|---|---:|---:|---|
| Prometheus | `prom/prometheus:v2.55.1` | `9090` | `9090` | `orynx-monitoring` |
| Grafana | `grafana/grafana:11.3.1` | `3000` | `3001` | `orynx-monitoring` |
| Grafana Tempo | `grafana/tempo:2.6.1` | `3200`, `4317`, `4318` | `3200` | `orynx-monitoring` |
| OTel Collector | `otel/opentelemetry-collector-contrib:0.114.0` | `4317`, `4318`, `8888`, `13133` | Internal only | `orynx-monitoring` |
| Postgres Exporter | `prometheuscommunity/postgres-exporter:v0.16.0` | `9187` | Internal only | `orynx-backend`, `orynx-monitoring` |
| Redis Exporter | `oliver006/redis_exporter:v1.66.0` | `9121` | Internal only | `orynx-backend`, `orynx-monitoring` |
| Kafka Exporter | `danielqsj/kafka-exporter:v1.8.0` | `9308` | Internal only | `orynx-backend`, `orynx-monitoring` |
| Backend Metrics | Spring Boot `PrometheusMetricsServer` | `9100` | Internal only | `orynx-backend`, `orynx-monitoring` |

---

## 3. Metrics Collection & Custom Workflow Metrics

### Standard Micrometer & JVM Metrics
All 5 Spring Boot services expose:
- `http_server_requests_seconds_*` (with percentile histograms enabled via `management.metrics.distribution.percentiles-histogram.http.server.requests=true`)
- `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`, `jvm_threads_live_threads`, `process_cpu_usage`
- Common tag: `application=${spring.application.name}`

### Custom Business Metrics (`orchestrator-service`)
`WorkflowMetrics` (`com.orynx.orchestrator.common.metrics.WorkflowMetrics`) records bounded-cardinality workflow and task metrics:
- `orynx_workflows_total` (Micrometer `orynx.workflows.created`) — Counter incremented when a workflow is created.
- `orynx_workflows_started_total` — Counter incremented when a workflow is started.
- `orynx_workflows_completed_total{status}` — Counter incremented on terminal workflow state (`COMPLETED`, `FAILED`).
- `orynx_workflows_duration_seconds{status}` — Timer measuring workflow execution duration by terminal status.
- `orynx_tasks_executed_total{status}` — Counter incremented on task completion/failure (`COMPLETED`, `FAILED`).

**Cardinality Guardrail**: High-cardinality identifiers (`userId`, `workflowId`, `taskId`, `requestId`) are **never** attached as Prometheus metric tags.

---

## 4. Distributed Tracing (OpenTelemetry + Tempo)

Each backend Dockerfile bundles the pinned OpenTelemetry Java Agent (`opentelemetry-javaagent-2.11.0.jar`).
- Controlled via `OTEL_JAVAAGENT_ENABLED` (default `true` in Compose; set `false` to disable without rebuilding).
- Exports OTLP/gRPC spans (`OTEL_EXPORTER_OTLP_PROTOCOL=grpc`) to `http://otel-collector:4317`, which batches and forwards to `tempo:4317`.
- Traces propagate automatically across:
  - HTTP calls (`gateway-service` → `auth-service` / `orchestrator-service` → `planning-service`)
  - Kafka producer/consumer headers (`orchestrator-service` ↔ `execution-service`, with `OTEL_INSTRUMENTATION_KAFKA_EXPERIMENTAL_SPAN_ATTRIBUTES=true`)
- Sampling is controlled by `OTEL_TRACES_SAMPLER=parentbased_traceidratio` and `OTEL_TRACES_SAMPLER_ARG` (default `1.0` for full visibility in dev/staging; lower in high-volume production).
- Tempo stores blocks and WAL in `orynx_tempo_data` with `24h` retention (`monitoring/tempo/tempo.yaml`).

---

## 5. Structured Logging & Trace Correlation

All 5 backend services configure `src/main/resources/logback-spring.xml`:
- **`docker` Spring profile (`SPRING_PROFILES_ACTIVE=docker`)**: Emits single-line JSON logs via `net.logstash.logback:logstash-logback-encoder:8.0` containing `timestamp`, `level`, `logger`, `thread`, `message`, `service`, and MDC fields (`trace_id`, `span_id`, `requestId`, `workflowId`).
- **Non-Docker local profiles (`!docker`)**: Emits human-readable console logs including `[service,trace_id,span_id,requestId]`.

**Sensitive Data Guardrail**: Never log JWT tokens, passwords, API keys, or raw request/response bodies.

---

## 6. Infrastructure Exporters

- **PostgreSQL (`postgres-exporter`)**: Exposes `pg_up`, `pg_stat_activity_count`, `pg_stat_database_xact_commit`, and connection/table stats.
- **Redis (`redis-exporter`)**: Exposes `redis_up`, `redis_connected_clients`, `redis_memory_used_bytes`, and command throughput.
- **Kafka (`kafka-exporter`)**: Exposes `kafka_brokers`, `kafka_topic_partitions`, `kafka_topic_partition_current_offset`, and `kafka_consumergroup_lag`.

---

## 7. Provisioned Grafana Dashboards

Grafana (`http://localhost:3001`) automatically provisions **Prometheus** (default) and **Tempo** datasources (`monitoring/grafana/provisioning/datasources/datasources.yml`) and 5 dashboards under folder **OrynX**:
1. **OrynX Platform Overview** (`orynx-platform-overview.json`): Services up, total RPS, 5xx rate, workflows started, RPS & P95 latency by service.
2. **OrynX Backend Service Health** (`orynx-backend-service-health.json`): JVM heap usage, CPU usage, live threads, GC pause rate.
3. **OrynX Workflow Execution** (`orynx-workflow-execution.json`): Workflows created/started/completed/failed, task execution rate, workflow duration.
4. **OrynX Kafka Messaging** (`orynx-kafka-messaging.json`): Brokers, consumer group lag by topic/group, topic message rate.
5. **OrynX Infrastructure** (`orynx-infrastructure.json`): Postgres & Redis health, active connections, committed transactions, Redis memory.

---

## 8. Operations & Verification

### Starting the Stack with Observability
1. Ensure `.env` defines `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` (see `.env.example`).
2. Validate configuration:
   ```bash
   docker compose --profile observability config -q
   ```
3. Build and start:
   ```bash
   docker compose build
   docker compose --profile observability up -d
   ```

### Verifying Health & Telemetry
- **Prometheus Targets**: Open `http://localhost:9090/targets` or query `http://localhost:9090/api/v1/targets` — all jobs (`orynx-backend`, `otel-collector`, `postgres-exporter`, `redis-exporter`, `kafka-exporter`) should report `up`.
- **Grafana**: Open `http://localhost:3001` and log in with `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`.
- **Tempo Traces**: Query `http://localhost:3200/api/search` or use Grafana Explore → Tempo.
- **Structured Logs**:
  ```bash
  docker compose logs --tail=20 orchestrator-service
  ```
  Verify JSON lines include `"trace_id"` and `"span_id"` during traced requests.

### Stopping
```bash
docker compose --profile observability down
```
(Add `-v` only if you intentionally want to wipe persistent volumes including Prometheus/Tempo/Grafana data.)
