# Runbook: ServiceUnavailable (`BackendServiceDown`, `PrometheusTargetDown`, `OtelCollectorDown`, `AlertmanagerDown`, `OtelCollectorSpanDrops`)

## 1. What the Alert Means
One or more OrynX platform microservices (`gateway-service`, `auth-service`, `orchestrator-service`, `planning-service`, `execution-service`) or observability pipeline components (`otel-collector`, `alertmanager`, or a Prometheus scrape target) has failed Prometheus health/scrape checks (`up == 0`) or is dropping telemetry spans for longer than the evaluation window (`1m`–`2m`).

## 2. User / Business Impact
- **`gateway-service` down**: All external REST and WebSocket traffic to OrynX fails.
- **`auth-service` down**: User login, registration, and token generation fail.
- **`orchestrator-service` down**: Workflow creation, lifecycle actions (`start`, `pause`, `resume`, `cancel`, `retry`), and real-time status updates fail.
- **`planning-service` down**: AI workflow generation (`/api/workflows/ai`) fails.
- **`execution-service` down**: Distributed DAG task execution stalls.
- **`otel-collector` / `alertmanager` down**: Distributed trace ingestion or alert notification delivery is interrupted.

## 3. Initial Investigation Steps
1. Identify the affected `service` and `instance` labels from the firing alert in Alertmanager or Grafana.
2. Check whether the container is running, restarting, or OOM-killed (`ExitCode 137`).
3. Inspect the last 100 lines of structured JSON container logs for startup exceptions, database/Kafka connectivity errors, or out-of-memory conditions.
4. Check if a backing infrastructure alert (`PostgreSQLUnavailable`, `KafkaUnavailable`, `RedisUnavailable`) is also firing.

## 4. Useful Commands
```bash
# Check container states, health status, and restart counts
docker compose --profile observability ps

# Inspect container exit code, OOMKilled flag, and healthcheck logs
docker inspect --format '{{json .State}}' <container_name>

# Tail structured JSON logs for the affected service
docker compose --profile observability logs --tail=100 <service_name>

# Verify internal metrics endpoint reachability from Prometheus
docker compose --profile observability exec prometheus wget -qO- http://orchestrator-service:9100/metrics | head -n 20
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Backend Services Availability Ratio* and *Active Prometheus Alerts* panels.
- **OrynX System Overview** (`uid: orynx-system-overview`) — *Service Up Status*, *JVM Heap Memory Used*, and *Process CPU Usage* panels.

## 6. Relevant Prometheus Queries
```promql
# Check which targets are currently down
up == 0

# Backend availability ratio across all 5 microservices
orynx:backend:availability_ratio

# JVM heap utilization percentage prior to outage
jvm_memory_used_bytes{area="heap"} / clamp_min(jvm_memory_max_bytes{area="heap"}, 1)

# OTel Collector failed or dropped span rates
sum(rate(otelcol_exporter_send_failed_spans_total[5m])) + sum(rate(otelcol_processor_dropped_spans_total[5m]))
```

## 7. Likely Root Causes
- **Container Crash / OOM**: JVM or collector exceeded its container `mem_limit`.
- **Backing Dependency Outage**: Service failed readiness probe because PostgreSQL (`postgres:5432`), Redis (`redis:6379`), or Kafka (`kafka:9092`) is unreachable.
- **Network / Interface Misconfiguration**: `ORYNX_MONITORING_NETWORK_PREFIX` does not match the `orynx-monitoring` subnet (`172.29.22.0/24`), preventing `PrometheusMetricsServer` from binding to `:9100`.
- **Tempo Backpressure**: For `OtelCollectorSpanDrops`, `tempo:4317` is unreachable or rate-limiting span ingestion.

## 8. Safe Recovery Actions
1. If PostgreSQL, Redis, or Kafka is down, restore the backing store first (see `PostgreSQLUnavailable.md`, `RedisUnavailable.md`, `KafkaUnavailable.md`).
2. Restart the affected service container cleanly:
   ```bash
   docker compose --profile observability up -d <service_name>
   ```
3. If the container was OOM-killed (`"OOMKilled":true`), increase its memory limit in `.env` (or tune `JAVA_OPTS=-XX:MaxRAMPercentage=70.0`) and recreate the container.
4. For `OtelCollectorSpanDrops`, verify `tempo` health (`docker compose --profile observability ps tempo`) and reduce `OTEL_TRACES_SAMPLER_ARG` (e.g., from `1.0` to `0.25`) under heavy load.

## 9. Escalation Criteria
- Escalate to the **Platform Infrastructure Lead** if the container fails to stay healthy after 2 restart attempts or exhibits repeated `OOMKilled` events.
- Escalate to the **Backend Service Owner** if logs show unhandled `NullPointerException`, `BeanCreationException`, or Flyway/schema migration failures.

## 10. Verification After Recovery
1. Confirm `docker compose --profile observability ps` reports `(healthy)` for the service.
2. Verify in Prometheus that `up{job="<job>"} == 1` and `orynx:backend:availability_ratio == 1`.
3. Confirm `BackendServiceDown` / `PrometheusTargetDown` transitions from `firing` to resolved in Alertmanager (`http://127.0.0.1:9093/#/alerts`).
