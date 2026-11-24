# OrynX Alerting Architecture & Alertmanager Guide (Step 23 / 23.2)

## 1. Architecture Overview

OrynX evaluates **25 actionable Prometheus alert rules** (`monitoring/prometheus/rules/alert-rules.yml`) every `15s` and dispatches firing/resolved alerts to **Alertmanager** (`prom/alertmanager:v0.27.0`, `monitoring/alertmanager/alertmanager.yml`) on the isolated `orynx-monitoring` network.

```text
+-----------------------------------------------------------------------------+
| orynx-monitoring (172.29.22.0/24)                                           |
|                                                                             |
|  +---------------------------+      HTTP POST       +--------------------+  |
|  | Prometheus (v2.55.1)      | -------------------> | Alertmanager       |  |
|  | - slo-recording-rules.yml |   /api/v2/alerts     | (v0.27.0)          |  |
|  | - alert-rules.yml         |                      | 127.0.0.1:9093     |  |
|  | - 35d / 10GB retention    |                      +---------+----------+  |
|  +-------------+-------------+                                |             |
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

### Security, Webhook Delivery & Operational Design
- **Loopback-Only Host Exposure**: Alertmanager publishes `127.0.0.1:9093:9093` on the host and attaches exclusively to `orynx-monitoring`.
- **Honest Default Notification Mode (`ALERTMANAGER_WEBHOOK_URL` Unset)**:
  - When `ALERTMANAGER_WEBHOOK_URL` is unset or empty, Alertmanager configures local receivers without external `webhook_configs`. Alerts are evaluated, routed, grouped, inhibited, and visible in the Alertmanager UI/API (`http://127.0.0.1:9093/#/alerts`) and Grafana Alertmanager datasource **without** attempting fake webhook POSTs or generating HTTP 405 errors. External notification delivery is explicitly disabled until a valid `http://` or `https://` `ALERTMANAGER_WEBHOOK_URL` is configured.
- **Safe Webhook Rendering & Validation (`monitoring/alertmanager/render-config.sh`)**:
  - When `ALERTMANAGER_WEBHOOK_URL` is set, `render-config.sh` validates the URL (rejecting whitespace-only values, non-`http(s)://` schemes, spaces, quotes, and backslashes), renders it via `awk` string concatenation (`ENVIRON["ALERTMANAGER_WEBHOOK_URL"]` — never `sed` replacement, preserving `&`, `?`, `=`, and `%` characters literally), and validates the generated YAML with `/bin/amtool check-config` before starting Alertmanager.
  - **Secret Hygiene**: If your webhook URL contains a bearer token or secret query parameter, note that container environment variables are visible to host operators via `docker inspect`; in production, inject secrets via Docker secrets or a vault-backed config mount.
- **Resource Limits**: Bounded by `ALERTMANAGER_MEM_LIMIT` (`128m` default) and `ALERTMANAGER_CPU_LIMIT` (`0.25` default).

---

## 2. Alertmanager Routing, Grouping & Inhibition (`monitoring/alertmanager/alertmanager.yml`)

### 2.1 Grouping & Deduplication
All alerts are grouped by `['alertname', 'service', 'category']` (`group_wait: 15s`, `group_interval: 1m`, `repeat_interval: 4h`).

### 2.2 Severity & Category Routing Tree
| Route Matchers | Receiver | `group_wait` | `group_interval` | `repeat_interval` | Purpose |
|---|---|---|---|---|---|
| `severity = "critical"` | `critical-webhook` | `10s` | `30s` | `1h` | Immediate delivery for service outages, backing store/exporter failures, high failure rates, and fast SLO burn |
| `category = "slo"` | `slo-webhook` | `15s` | `1m` | `2h` | Slow SLO burn warnings and error budget notifications |
| `category =~ "workflow\|task"` | `workflow-webhook` | `15s` | `1m` | `2h` | Workflow latency, stalled completion, retry elevation, and stalled task execution |
| `category =~ "infrastructure\|platform"` | `infrastructure-webhook` | `15s` | `1m` | `2h` | Exporter warnings, Postgres connection utilization, Kafka consumer lag, OTel span drops |
| `severity = "warning"` | `warning-webhook` | `30s` | `2m` | `4h` | Fallback route for general warning alerts |
| *(root default)* | `default-webhook` | `15s` | `1m` | `4h` | Catch-all route (`info` / unmatched alerts) |

### 2.3 Alert Inhibition Rules (Scoped Noise Suppression)
Alertmanager enforces **4 scoped inhibition rules**:
1. **Critical Inhibits Warning (`equal: [service, category]`)**:
   - When a `severity="critical"` alert is firing for `(service, category)`, `severity="warning"` alerts for the exact same `(service, category)` are suppressed (e.g., `ApiSloFastBurnCritical` inhibits `ApiSloSlowBurnWarning`).
2. **Core Infrastructure Outage Inhibits Warning-Level Workflow/Task Symptoms (`equal: []`)**:
   - When `PostgreSQLUnavailable` or `KafkaUnavailable` is firing, `severity="warning"` alerts in `category =~ "workflow|task"` are inhibited, while **`severity="critical"` workflow, task, and SLO alerts remain visible**. `RedisUnavailable` is excluded because current OrynX microservices do not use Redis for workflow execution.
3. **Backend Service Down Inhibits Warning-Level Service-Scoped Symptoms (`equal: [service]`)**:
   - `BackendServiceDown` normalizes `service` by stripping the `:9100` port (`{{ $labels.instance | reReplaceAll ":[0-9]+$" "" }}`), enabling exact `equal: [service]` matching against `orchestrator-service` / `execution-service` `severity="warning"` workflow/task/SLO alerts while keeping critical alerts visible.
4. **Specific Target-Down Alerts Inhibit Generic `PrometheusTargetDown` (`equal: [instance]`)**:
   - `BackendServiceDown`, `OtelCollectorDown`, `PostgresExporterDown`, `RedisExporterDown`, `KafkaExporterDown`, and `AlertmanagerDown` suppress duplicate `PrometheusTargetDown` notifications for the same `instance`.

---

## 3. Complete Prometheus Alert Catalog (`monitoring/prometheus/rules/alert-rules.yml`)

Every alert rule defines `severity`, `category`, `service`, `for`, `summary`, `description`, `impact`, and `runbook_url`.

### 3.1 Platform & Observability Alerts (`orynx_platform_alerts`, 7 rules)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `BackendServiceDown` | `critical` | `platform` | `1m` | `up{job="orynx-backend"} == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |
| `PrometheusTargetDown` | `warning` | `platform` | `2m` | `up == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |
| `OtelCollectorDown` | `critical` | `platform` | `1m` | `up{job="otel-collector"} == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |
| `PostgresExporterDown` | `critical` | `platform` | `1m` | `up{job="postgres-exporter"} == 0` | [PostgreSQLUnavailable.md](./runbooks/PostgreSQLUnavailable.md) |
| `RedisExporterDown` | `warning` | `platform` | `1m` | `up{job="redis-exporter"} == 0` | [RedisUnavailable.md](./runbooks/RedisUnavailable.md) |
| `KafkaExporterDown` | `critical` | `platform` | `1m` | `up{job="kafka-exporter"} == 0` | [KafkaUnavailable.md](./runbooks/KafkaUnavailable.md) |
| `AlertmanagerDown` | `critical` | `platform` | `1m` | `up{job="alertmanager"} == 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |

### 3.2 Workflow Orchestration Alerts (`orynx_workflow_alerts`, 4 rules)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `WorkflowFailureRateHigh` | `critical` | `workflow` | `2m` | `orynx:workflow:failure_ratio:rate5m > 0.10 and sum(increase(orynx_workflows_completed_total{status=~"COMPLETED\|FAILED"}[5m])) >= 3` | [WorkflowFailureRateHigh.md](./runbooks/WorkflowFailureRateHigh.md) |
| `WorkflowExecutionLatencyHigh` | `warning` | `workflow` | `3m` | `orynx:workflow:duration_p95_seconds:rate5m > 30 and (completed + failed) > 0` | [WorkflowLatencyHigh.md](./runbooks/WorkflowLatencyHigh.md) |
| `WorkflowCompletionDegraded` | `warning` | `workflow` | `5m` | `orynx:workflow:started:rate5m > 0 and (completed + failed) == 0` | [WorkflowLatencyHigh.md](./runbooks/WorkflowLatencyHigh.md) |
| `WorkflowRetryRateElevated` | `warning` | `workflow` | `3m` | `orynx:workflow:retry_ratio:rate5m > 0.20 and sum(increase(orynx_workflows_started_total[5m])) >= 3` | [WorkflowFailureRateHigh.md](./runbooks/WorkflowFailureRateHigh.md) |

### 3.3 Task Execution Alerts (`orynx_task_alerts`, 2 rules)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `TaskFailureRateHigh` | `critical` | `task` | `2m` | `orynx:task:failure_ratio:rate5m > 0.10 and sum(increase(orynx_tasks_executed_total{status=~"COMPLETED\|FAILED"}[5m])) >= 5` | [TaskFailureRateHigh.md](./runbooks/TaskFailureRateHigh.md) |
| `TaskExecutionStalled` | `warning` | `task` | `5m` | `orynx:workflow:started:rate5m > 0 and orynx:task:executed:rate5m == 0` | [TaskFailureRateHigh.md](./runbooks/TaskFailureRateHigh.md) |

### 3.4 Infrastructure & Backing Store Alerts (`orynx_infrastructure_alerts`, 6 rules)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `PostgreSQLUnavailable` | `critical` | `infrastructure` | `1m` | `pg_up == 0` | [PostgreSQLUnavailable.md](./runbooks/PostgreSQLUnavailable.md) |
| `PostgreSQLConnectionsHigh` | `warning` | `infrastructure` | `3m` | `orynx:postgres:connection_utilization_ratio > 0.80` | [PostgreSQLUnavailable.md](./runbooks/PostgreSQLUnavailable.md) |
| `RedisUnavailable` | `critical` | `infrastructure` | `1m` | `redis_up == 0` | [RedisUnavailable.md](./runbooks/RedisUnavailable.md) |
| `KafkaUnavailable` | `critical` | `infrastructure` | `1m` | `kafka_brokers < 1` | [KafkaUnavailable.md](./runbooks/KafkaUnavailable.md) |
| `KafkaConsumerLagHigh` | `warning` | `infrastructure` | `3m` | `orynx:kafka:consumer_lag:by_group_topic > 100` | [KafkaUnavailable.md](./runbooks/KafkaUnavailable.md) |
| `OtelCollectorSpanDrops` | `warning` | `infrastructure` | `2m` | `sum(rate(otelcol_exporter_send_failed_spans_total[5m])) > 0 or sum(rate(otelcol_processor_dropped_spans_total[5m])) > 0` | [ServiceUnavailable.md](./runbooks/ServiceUnavailable.md) |

### 3.5 SLO Burn & Error Budget Alerts (`orynx_slo_burn_alerts`, 6 rules)
| Alert Name | Severity | Category | `for` | Trigger Condition (`expr`) | Runbook |
|---|---|---|---|---|---|
| `ApiSloFastBurnCritical` | `critical` | `slo` | `2m` | `burn_rate:1h > 14.4 and burn_rate:5m > 14.4` (min 5 req/5m, 20 req/1h) | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `ApiSloSlowBurnWarning` | `warning` | `slo` | `15m` | `burn_rate:6h > 6 and burn_rate:30m > 6` (min 10 req/30m, 40 req/6h) | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `WorkflowSloFastBurnCritical` | `critical` | `slo` | `2m` | `burn_rate:1h > 14.4 and burn_rate:5m > 14.4` (min 3 workflows/5m) | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `WorkflowSloSlowBurnWarning` | `warning` | `slo` | `15m` | `burn_rate:6h > 6 and burn_rate:30m > 6` (min 5 workflows/30m) | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `TaskSloFastBurnCritical` | `critical` | `slo` | `2m` | `burn_rate:1h > 14.4 and burn_rate:5m > 14.4` (min 5 tasks/5m) | [SLOBurnRateHigh.md](./runbooks/SLOBurnRateHigh.md) |
| `ErrorBudgetExhausted` | `critical` | `slo` | `5m` | `orynx:{api,workflow,task}:error_budget_remaining:{ratio_30d,observed_30d} <= 0` | [ErrorBudgetExhausted.md](./runbooks/ErrorBudgetExhausted.md) |
