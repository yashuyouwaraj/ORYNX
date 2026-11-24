# OrynX Service-Level Objectives (SLOs), SLIs & Error Budget Model (Step 23 / 23.2)

## 1. Overview & Design Philosophy

OrynX defines production-grade **Service-Level Indicators (SLIs)**, **Service-Level Objectives (SLOs)**, **30-day rolling Error Budgets**, and **Google SRE multi-window, multi-burn-rate alerts** implemented via Prometheus recording rules (`monitoring/prometheus/rules/slo-recording-rules.yml`) and visualized in the **OrynX SLO & Operational Reliability** Grafana dashboard (`monitoring/grafana/dashboards/orynx-slo-reliability.json`).

Key reliability principles:
1. **Probe Exclusion**: All HTTP API availability and latency SLIs explicitly exclude `/actuator.*` (`uri!~"/actuator.*"`), ensuring high-frequency container readiness/liveness probes never artificially inflate request volume or mask user-facing errors.
2. **Missing-Telemetry Awareness vs. Idle Semantics**:
   - **Idle-but-healthy** (`count(up{job="orynx-backend"} == 1) > 0` with zero HTTP traffic, or pre-registered `WorkflowMetrics` counters at `0` while `orchestrator-service` is up) evaluates to `0.0` error ratio (`1.0` / `100%` availability, `0.0` burn rate).
   - **Missing telemetry or total backend outage** (`count(up{job="orynx-backend"} == 1) == 0` or missing `orynx_workflows_completed_total` / `orynx_tasks_executed_total` / exporter series) evaluates to **empty (`No Data`)** rather than falsely reporting `100%` availability, `100%` error budget, or `0` Kafka lag.
   - **Request-based proxy vs. reachability**: API availability (`orynx:api:availability:ratio_rate<w>`) measures HTTP 5xx ratio across observed requests (including internal gateway-to-service hops). Service reachability is tracked independently via `orynx:backend:availability_ratio` (`up{job="orynx-backend"} == 1`).
3. **Prometheus Retention & 30-Day History Coverage**:
   - Prometheus runs with `--storage.tsdb.retention.time=${PROMETHEUS_RETENTION_TIME:-35d}` and `--storage.tsdb.retention.size=${PROMETHEUS_RETENTION_SIZE:-10GB}` so the 30-day rolling SLO window has a 5-day retention margin while capping TSDB disk growth.
   - Storage tradeoff: At `15s` scrape interval across 11 targets, 35-day TSDB retention typically consumes ~1–4 GB of `orynx_prometheus_data` volume space (bounded at `10GB` by `--storage.tsdb.retention.size`).
   - **Fresh-deployment honesty**: `orynx:slo:history_coverage_seconds` measures `time() - max(prometheus_tsdb_lowest_timestamp_seconds)`. The official 30-day gauges (`orynx:*:error_budget_remaining:ratio_30d`) require `>= 30d` (`2592000s`) of TSDB history and display **"Insufficient 30d History"** on fresh deployments rather than a misleading `100%`. Observed-window tracking (`orynx:*:error_budget_remaining:observed_30d`) remains active for `ErrorBudgetExhausted` detection.
4. **Histogram Bounds for Workflow Duration**: `WorkflowMetrics` (`orynx.workflows.duration`) configures `minimumExpectedValue=100ms`, `maximumExpectedValue=15m` (`900s`), and explicit SLO buckets (`1s, 5s, 15s, 30s, 45s, 60s, 120s, 300s, 600s`) so `orynx:workflow:duration_p95_seconds:rate5m` accurately measures durations well above the `30.0s` SLO threshold.
5. **Bounded Cardinality**: No recording rule or alert rule groups by high-cardinality dimensions (`userId`, `workflowId`, `taskId`, `executionId`, `requestId`).

---

## 2. OrynX SLI & SLO Catalog

| Reliability Domain | SLI Metric (`slo-recording-rules.yml`) | Underlying Source Telemetry | SLO Target | 30-Day Error Budget | Rolling Windows Evaluated |
|---|---|---|---:|---:|---|
| **API Availability** | `orynx:api:availability:ratio_rate<window>` | `http_server_requests_seconds_count{uri!~"/actuator.*", outcome="SERVER_ERROR"}` vs total non-actuator requests | **99.9%** (`0.999`) | **0.1%** (`0.001`, ~43.2 min/30d) | `5m`, `30m`, `1h`, `6h`, `24h`, `7d`, `30d` |
| **API P95 Latency** | `orynx:api:latency_p95:rate5m` | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))` | **<= 0.5s** (`500ms`) | N/A (Latency threshold) | `5m` (plus P99 via `orynx:api:latency_p99:rate5m`) |
| **Workflow Completion Reliability** | `orynx:workflow:success_ratio:rate<window>` | `1 - (rate(orynx_workflows_completed_total{status="FAILED"}[w]) / rate(orynx_workflows_completed_total{status=~"COMPLETED\|FAILED"}[w]))` | **99.0%** (`0.99`) | **1.0%** (`0.01`) | `5m`, `30m`, `1h`, `6h`, `24h`, `7d`, `30d` |
| **Workflow Execution Duration (P95)** | `orynx:workflow:duration_p95_seconds:rate5m` | `histogram_quantile(0.95, sum by (le) (rate(orynx_workflows_duration_seconds_bucket[5m])))` | **<= 30.0s** | N/A (Duration threshold) | `5m` (plus average via `orynx:workflow:duration_avg_seconds:rate5m`) |
| **Task Execution Reliability** | `orynx:task:success_ratio:rate<window>` | `1 - (rate(orynx_tasks_executed_total{status="FAILED"}[w]) / rate(orynx_tasks_executed_total{status=~"COMPLETED\|FAILED"}[w]))` | **99.0%** (`0.99`) | **1.0%** (`0.01`) | `5m`, `30m`, `1h`, `6h`, `30d` |
| **Backend Service Availability** | `orynx:backend:availability_ratio` | `count(up{job="orynx-backend"} == 1) / count(up{job="orynx-backend"})` | **100%** (`1.0`, all 5 services up) | Critical alert after `1m` down | Instant (`15s` evaluation) |
| **Observability Trace Export** | `orynx:otel:export_success_ratio:rate5m` | `1 - (failed_spans / (sent_spans + failed_spans))` | **>= 99.9%** | Warning alert on any drop/failure for `2m` | `5m` |

---

## 3. Error Budget & Burn-Rate Mathematics

For any reliability SLO target $T \in (0, 1)$, the allowed error budget fraction over a 30-day window is:

$$E_{\text{budget}} = 1 - T$$

- **API Availability** ($T = 0.999$): $E_{\text{budget}} = 0.001$ ($0.1\%$).
- **Workflow Completion Reliability** ($T = 0.99$): $E_{\text{budget}} = 0.01$ ($1.0\%$).
- **Task Execution Reliability** ($T = 0.99$): $E_{\text{budget}} = 0.01$ ($1.0\%$).

### 3.1 Remaining Error Budget Ratio (30-Day Rolling)

$$\text{ErrorBudgetRemaining}_{30\text{d}} = \operatorname{clamp}\left(1 - \frac{\text{ErrorRatio}_{30\text{d}}}{1 - T},\ 0,\ 1\right) \quad \text{when } \text{history\_coverage\_seconds} \ge 2592000$$

- `1.0` (`100%`): Zero errors over the full 30-day window; full budget remaining.
- `0.5` (`50%`): Half of the allowed 30-day error budget has been consumed.
- `0.0` (`0%`): Error budget is completely exhausted (`ErrorBudgetExhausted` critical alert fires after `5m`).
- **Empty (`No Data`)**: Returned whenever TSDB history is `< 30d` (fresh deployment) or underlying telemetry is missing.

### 3.2 Multi-Window Burn Rate & Low-Traffic Safeguards

$$\text{BurnRate}_{w} = \frac{\text{ErrorRatio}_{w}}{1 - T}$$

| Burn Rate Tier | Short & Long Windows | Threshold | Minimum Traffic Guard | `for` Duration | Severity |
|---|---|---:|---|---|---|
| **API Fast Burn** | `5m` **AND** `1h` | `> 14.4x` | `>= 5` req/5m and `>= 20` req/1h | `2m` | `critical` |
| **API Slow Burn** | `30m` **AND** `6h` | `> 6.0x` | `>= 10` req/30m and `>= 40` req/6h | `15m` | `warning` |
| **Workflow Fast Burn** | `5m` **AND** `1h` | `> 14.4x` | `>= 3` terminal workflows/5m | `2m` | `critical` |
| **Workflow Slow Burn** | `30m` **AND** `6h` | `> 6.0x` | `>= 5` terminal workflows/30m | `15m` | `warning` |
| **Task Fast Burn** | `5m` **AND** `1h` | `> 14.4x` | `>= 5` tasks/5m | `2m` | `critical` |

**Tradeoff**: Minimum-traffic guards prevent a single isolated 5xx or failed workflow during near-zero traffic from waking on-call engineers as a statistical false positive, while ensuring genuine sustained degradation triggers alerts within the `for` window.

---

## 4. Recording Rule Groups (`monitoring/prometheus/rules/slo-recording-rules.yml`)

Total: **81 recording rules** across **4 groups** (verified automatically by `SloAndAlertingConfigValidationTest`):
1. **`orynx_api_sli_rules`** (29 rules): TSDB history coverage (`orynx:slo:history_coverage_seconds`, `orynx:slo:history_coverage_days`), SLO targets, request/error rates, error & availability ratios (`5m`, `30m`, `1h`, `6h`, `24h`, `7d`, `30d`), P95/P99 latencies, `observed_30d` & `ratio_30d` error budgets, and `5m`/`30m`/`1h`/`6h` burn rates.
2. **`orynx_workflow_sli_rules`** (30 rules): SLO targets, lifecycle rates (`created`, `started`, `completed`, `failed`, `cancelled`, `retried`, `retry_ratio:rate5m`), failure & success ratios (`5m`, `30m`, `1h`, `6h`, `24h`, `7d`, `30d`, excluding user-initiated `CANCELLED`), avg & P95 durations, `observed_30d` & `ratio_30d` error budgets, and `5m`/`30m`/`1h`/`6h` burn rates.
3. **`orynx_task_sli_rules`** (16 rules): SLO target, execution rates (`executed`, `completed`, `failed`), failure ratios (`5m`, `30m`, `1h`, `6h`, `30d`), success ratios (`5m`, `1h`, `30d`), `observed_30d` & `ratio_30d` error budgets, and `5m`/`1h` burn rates.
4. **`orynx_infrastructure_sli_rules`** (6 rules): `orynx:backend:availability_ratio`, `orynx:postgres:connection_utilization_ratio`, `orynx:kafka:consumer_lag:by_group_topic`, `orynx:kafka:consumer_lag:max`, `orynx:otel:export_failure_ratio:rate5m`, and `orynx:otel:export_success_ratio:rate5m`.
