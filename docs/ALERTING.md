# OrynX Alerting Architecture & Alertmanager Guide (Step 23)

## 1. Architecture Overview

OrynX evaluates 25 actionable Prometheus alert rules (`monitoring/prometheus/rules/alert-rules.yml`) every `15s` and dispatches firing/resolved alerts to **Alertmanager** (`prom/alertmanager:v0.27.0`, `monitoring/alertmanager/alertmanager.yml`) on the isolated `orynx-monitoring` network.

```text
+-----------------------------------------------------------------------------+
| orynx-monitoring (172.29.22.0/24)                                           |
|                                                                             |
|  +---------------------------+      HTTP POST       +--------------------+  |
|  | Prometheus (v2.55.1)      | -------------------> | Alertmanager       |  |
|  | - slo-recording-rules.yml |   /api/v2/alerts     | (v0.27.0)          |  |
|  | - alert-rules.yml         |                      | 127.0.0.1:9093     |  |
|  +-------------+-------------+                      +---------+----------+  |
|                |                                              |             |
|                | PromQL + ALERTS{...}                         | Datasource  |
|                +----------------------+-----------------------+             |
|                                       v                                     |
|                        +------------------------------+                     |
|                        | Grafana (11.3.1)             |                     |
|                        | - orynx-slo-reliability.json |                     |
|                        | - Alertmanager Datasource    |                     |
|                        +------------------------------+                     |
+-----------------------------------------------------------------------------+
```

### Security & Operational Design
- **Loopback-Only Host Exposure**: Alertmanager publishes `127.0.0.1:9093:9093` on the host and attaches exclusively to `orynx-monitoring`.
- **Zero External Secret Requirement by Default**: Alertmanager receivers default to `ALERTMANAGER_WEBHOOK_URL=http://127.0.0.1:9093/-/healthy` (an internal loopback HTTP 200 sink), allowing the full alerting pipeline (evaluation, routing, grouping, inhibition, and webhook delivery) to run out of the box in local and CI environments while supporting external webhook override via `.env`.
- **Resource Limits**: Bounded by `ALERTMANAGER_MEM_LIMIT` (`128m` default) and `ALERTMANAGER_CPU_LIMIT` (`0.25` default).

---

## 2. Alertmanager Routing, Grouping & Inhibition (`monitoring/alertmanager/alertmanager.yml`)

### 2.1 Grouping & Deduplication
All alerts are grouped by:
```yaml
group_by: ['alertname', 'service', 'category']
```
This consolidates simultaneous alerts for the same condition and service into a single notification batch while keeping distinct services and alert categories separated.

### 2.2 Severity & Category Routing Tree
| Route Matchers | Receiver | `group_wait` | `group_interval` | `repeat_interval` | Purpose |
|---|---|---|---|---|---|
| `severity = "critical"` | `critical-webhook` | `10s` | `30s` | `1h` | Immediate delivery for service outages, backing store failures, high failure rates, and fast SLO burn |
| `category = "slo"` | `slo-webhook` | `15s` | `1m` | `2h` | Slow SLO burn warnings and error budget notifications |
| `category =~ "workflow\|task"` | `workflow-webhook` | `15s` | `1m` | `2h` | Workflow latency, stalled completion, retry elevation, and stalled task execution |
| `category =~ "infrastructure\|platform"` | `infrastructure-webhook` | `15s` | `1m` | `2h` | Exporter down, Postgres connection utilization, Kafka consumer lag, OTel span drops |
| `severity = "warning"` | `warning-webhook` | `30s` | `2m` | `4h` | Fallback route for general warning alerts |
| *(root default)* | `default-webhook` | `15s` | `1m` | `4h` | Catch-all route (`info` / unmatched alerts) |

### 2.3 Alert Inhibition Rules (Noise Suppression)
Alertmanager enforces 3 inhibition rules to prevent cascading alert floods during root-cause outages:
1. **Critical Inhibits Warning (`equal: [service, category]`)**:
   - When a `severity="critical"` alert is firing for a given `(service, category)`, any `severity="warning"` alert for the same `(service, category)` is suppressed (e.g., `ApiSloFastBurnCritical` inhibits `ApiSloSlowBurnWarning`; `WorkflowFailureRateHigh` inhibits `WorkflowRetryRateElevated`).
2. **Core Infrastructure Outage Inhibits Downstream Workflow/Task/SLO Alerts**:
   - When `PostgreSQLUnavailable`, `KafkaUnavailable`, or `RedisUnavailable` is firing, downstream symptoms in `category =~ "workflow|task|slo"` are inhibited so operators focus on restoring the failed backing store.
3. **Backend Service Down Inhibits Service-Scoped Workflow/Task/SLO Alerts (`equal: [service]`)**:
   - When `BackendServiceDown` fires for a service, secondary `slo`, `workflow`, or `task` alerts targeting that same `service` are inhibited.

---

## 3. Complete Prometheus Alert Catalog (`monitoring/prometheus/rules/alert-rules.yml`)

Every alert rule defines `severity`, `category`, `service`, `for`, `summary`, `description`, `impact`, and `runbook_url`.

### 3.1 Platform & Observability Alerts (`orynx_platform_alerts`)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `BackendServiceDown` | `critical` | `platform` | `1m` | `up{job="orynx-backend"} == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |
| `PrometheusTargetDown` | `warning` | `platform` | `2m` | `up == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |
| `OtelCollectorDown` | `critical` | `platform` | `1m` | `up{job="otel-collector"} == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |
| `PostgresExporterDown` | `warning` | `platform` | `1m` | `up{job="postgres-exporter"} == 0` | [PostgreSQLUnavailable.md](./runbooks/PostgreSQLUnavailable.md) |
| `RedisExporterDown` | `warning` | `platform` | `1m` | `up{job="redis-exporter"} == 0` | [RedisUnavailable.md](./runbooks/RedisUnavailable.md) |
| `KafkaExporterDown` | `warning` | `platform` | `1m` | `up{job="kafka-exporter"} == 0` | [KafkaUnavailable.md](./runbooks/KafkaUnavailable.md) |
| `AlertmanagerDown` | `critical` | `platform` | `1m` | `up{job="alertmanager"} == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |

### 3.2 Workflow Orchestration Alerts (`orynx_workflow_alerts`)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `WorkflowFailureRateHigh` | `critical` | `workflow` | `2m` | `orynx:workflow:failure_ratio:rate5m > 0.10 and (completed + failed) > 0` | [WorkflowFailureRateHigh.md](./runbooks/WorkflowFailureRateHigh.md) |
| `WorkflowExecutionLatencyHigh` | `warning` | `workflow` | `3m` | `orynx:workflow:duration_p95_seconds:rate5m > 30 and (completed + failed) > 0` | [WorkflowLatencyHigh.md](./runbooks/WorkflowLatencyHigh.md) |
| `WorkflowCompletionDegraded` | `warning` | `workflow` | `5m` | `orynx:workflow:started:rate5m > 0 and (completed + failed) == 0` | [WorkflowLatencyHigh.md](./runbooks/WorkflowLatencyHigh.md) |
| `WorkflowRetryRateElevated` | `warning` | `workflow` | `3m` | `orynx:workflow:retry_ratio:rate5m > 0.20 and orynx:workflow:retried:rate5m > 0` | [WorkflowFailureRateHigh.md](./runbooks/WorkflowFailureRateHigh.md) |

### 3.3 Task Execution Alerts (`orynx_task_alerts`)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `TaskFailureRateHigh` | `critical` | `task` | `2m` | `orynx:task:failure_ratio:rate5m > 0.10 and orynx:task:executed:rate5m > 0` | [TaskFailureRateHigh.md](./runbooks/TaskFailureRateHigh.md) |
| `TaskExecutionStalled` | `warning` | `task` | `5m` | `orynx:workflow:started:rate5m > 0 and orynx:task:executed:rate5m == 0` | [TaskFailureRateHigh.md](./runbooks/TaskFailureRateHigh.md) |

### 3.4 Infrastructure & Backing Store Alerts (`orynx_infrastructure_alerts`)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `PostgreSQLUnavailable` | `critical` | `infrastructure` | `1m` | `pg_up == 0` | [PostgreSQLUnavailable.md](./runbooks/PostgreSQLUnavailable.md) |
| `PostgreSQLConnectionsHigh` | `warning` | `infrastructure` | `3m` | `orynx:postgres:connection_utilization_ratio > 0.80` | [PostgreSQLUnavailable.md](./runbooks/PostgreSQLUnavailable.md) |
| `RedisUnavailable` | `critical` | `infrastructure` | `1m` | `redis_up == 0` | [RedisUnavailable.md](./runbooks/RedisUnavailable.md) |
| `KafkaUnavailable` | `critical` | `infrastructure` | `1m` | `kafka_brokers < 1` | [KafkaUnavailable.md](./runbooks/KafkaUnavailable.md) |
| `KafkaConsumerLagHigh` | `warning` | `infrastructure` | `3m` | `orynx:kafka:consumer_lag:by_group_topic > 100` | [KafkaUnavailable.md](./runbooks/KafkaUnavailable.md) |
| `OtelCollectorSpanDrops` | `warning` | `infrastructure` | `2m` | `rate(otelcol_exporter_send_failed_spans_total[5m]) > 0 or rate(otelcol_processor_dropped_spans_total[5m]) > 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |

### 3.5 SLO Burn & Error Budget Alerts (`orynx_slo_burn_alerts`)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `ApiSloFastBurnCritical` | `critical` | `slo` | `2m` | `orynx:api:burn_rate:1h > 14.4 and orynx:api:burn_rate:5m > 14.4` | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `ApiSloSlowBurnWarning` | `warning` | `slo` | `15m` | `orynx:api:burn_rate:6h > 6 and orynx:api:burn_rate:30m > 6` | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `WorkflowSloFastBurnCritical` | `critical` | `slo` | `2m` | `orynx:workflow:burn_rate:1h > 14.4 and orynx:workflow:burn_rate:5m > 14.4` | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `WorkflowSloSlowBurnWarning` | `warning` | `slo` | `15m` | `orynx:workflow:burn_rate:6h > 6 and orynx:workflow:burn_rate:30m > 6` | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `TaskSloFastBurnCritical` | `critical` | `slo` | `2m` | `orynx:task:burn_rate:1h > 14.4 and orynx:task:burn_rate:5m > 14.4` | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `ErrorBudgetExhausted` | `critical` | `slo` | `5m` | `orynx:{api,workflow,task}:error_budget_remaining:ratio_30d <= 0` | [ErrorBudgetExhausted.md](./runbooks/ErrorBudgetExhausted.md) |

---

## 4. Validating Rules & Simulating Alerts

### 4.1 Offline Rule & Config Validation (`promtool` & `amtool`)
```bash
# Validate Prometheus recording and alert rules
docker run --rm --entrypoint /bin/promtool \
  -v "${PWD}/monitoring/prometheus/rules:/etc/prometheus/rules:ro" \
  prom/prometheus:v2.55.1 check rules \
  /etc/prometheus/rules/slo-recording-rules.yml \
  /etc/prometheus/rules/alert-rules.yml

# Run declarative Prometheus unit tests
docker run --rm --entrypoint /bin/promtool \
  -v "${PWD}/monitoring/prometheus/rules:/etc/prometheus/rules:ro" \
  -v "${PWD}/monitoring/prometheus/tests:/etc/prometheus/tests:ro" \
  prom/prometheus:v2.55.1 test rules /etc/prometheus/tests/rules-test.yml

# Validate Alertmanager configuration
docker run --rm --entrypoint /bin/amtool \
  -v "${PWD}/monitoring/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager:v0.27.0 check-config /etc/alertmanager/alertmanager.yml
```

### 4.2 Inspecting Live Alerts & Testing Alertmanager Routing
When the observability profile is running (`docker compose --profile observability up -d`):
- **Prometheus Loaded Rules**: `http://127.0.0.1:9090/api/v1/rules`
- **Prometheus Active Alerts**: `http://127.0.0.1:9090/api/v1/alerts`
- **Alertmanager Status**: `http://127.0.0.1:9093/api/v2/status`
- **Alertmanager Active Alerts**: `http://127.0.0.1:9093/api/v2/alerts`
