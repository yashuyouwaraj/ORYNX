# OrynX Service-Level Objectives (SLOs), SLIs & Error Budget Model (Step 23)

## 1. Overview & Design Philosophy

OrynX defines production-grade **Service-Level Indicators (SLIs)**, **Service-Level Objectives (SLOs)**, **30-day rolling Error Budgets**, and **Google SRE multi-window, multi-burn-rate alerts** implemented via Prometheus recording rules (`monitoring/prometheus/rules/slo-recording-rules.yml`) and visualized in the **OrynX SLO & Operational Reliability** Grafana dashboard (`monitoring/grafana/dashboards/orynx-slo-reliability.json`).

Key reliability principles:
1. **Probe Exclusion**: All HTTP API availability and latency SLIs explicitly exclude `/actuator.*` (`uri!~"/actuator.*"`), ensuring high-frequency container readiness/liveness probes never artificially inflate request volume or mask user-facing errors.
2. **Idle-Safe Zero-Denominator Semantics**: Recording rules use `clamp_min(..., 1e-12)` and `or vector(0)` so zero-traffic (idle) intervals evaluate to `0.0` error ratio (`1.0` / `100%` availability, `0.0` burn rate) rather than `NaN`. Idle periods never trigger false-positive SLO burn or failure alerts.
3. **Bounded Cardinality**: No recording rule or alert rule groups by high-cardinality dimensions (`userId`, `workflowId`, `taskId`, `executionId`, `requestId`).

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

$$\text{ErrorBudgetRemaining}_{30\text{d}} = \operatorname{clamp}\left(1 - \frac{\text{ErrorRatio}_{30\text{d}}}{1 - T},\ 0,\ 1\right)$$

- `1.0` (`100%`): Zero errors over the 30-day window; full budget remaining.
- `0.5` (`50%`): Half of the allowed 30-day error budget has been consumed.
- `0.0` (`0%`): Error budget is completely exhausted (`ErrorBudgetExhausted` critical alert fires after `5m`).

Recorded series:
- `orynx:api:error_budget_remaining:ratio_30d`
- `orynx:workflow:error_budget_remaining:ratio_30d`
- `orynx:task:error_budget_remaining:ratio_30d`

### 3.2 Multi-Window Burn Rate

The dimensionless **burn rate** over window $w$ measures how fast the service is consuming its 30-day error budget relative to a sustainable $1.0\times$ pace:

$$\text{BurnRate}_{w} = \frac{\text{ErrorRatio}_{w}}{1 - T}$$

| Burn Rate Tier | Short & Long Windows | Threshold | `for` Duration | Severity | Budget Consumed per Window | Time to Exhaust 30d Budget |
|---|---|---:|---|---|---:|---:|
| **Fast Burn** (`*SloFastBurnCritical`) | `5m` **AND** `1h` | **`> 14.4x`** | `2m` | `critical` | `2%` in 1 hour | ~50 hours (~2.08 days) |
| **Slow Burn** (`*SloSlowBurnWarning`) | `30m` **AND** `6h` | **`> 6.0x`** | `15m` | `warning` | `5%` in 6 hours | 120 hours (5.0 days) |

Requiring **both** the long window (`1h` or `6h`) and the short confirmation window (`5m` or `30m`) to exceed the burn threshold simultaneously ensures:
- High detection sensitivity during genuine incidents.
- Immediate alert reset as soon as the short window (`5m` or `30m`) recovers after remediation, avoiding hours of lingering false alarms while the `1h`/`6h` window ages out.

---

## 4. Recording Rule Groups (`monitoring/prometheus/rules/slo-recording-rules.yml`)

1. **`orynx_api_sli_rules`** (25 rules):
   - Targets: `orynx:api:slo_target_availability` (`0.999`), `orynx:api:slo_target_p95_seconds` (`0.5`).
   - Rates: `orynx:api:requests:rate5m`, `orynx:api:requests:rate5m_by_service`, `orynx:api:errors:rate5m`, `orynx:api:errors:rate5m_by_service`.
   - Error & availability ratios across `5m`, `30m`, `1h`, `6h`, `24h`, `7d`, `30d`.
   - Latency quantiles: `orynx:api:latency_p95:rate5m`, `orynx:api:latency_p99:rate5m`.
   - Budget & burn rates: `orynx:api:error_budget_remaining:ratio_30d`, `orynx:api:burn_rate:{5m,30m,1h,6h}`.
2. **`orynx_workflow_sli_rules`** (28 rules):
   - Targets: `orynx:workflow:slo_target_success_ratio` (`0.99`), `orynx:workflow:slo_target_p95_seconds` (`30.0`).
   - Lifecycle rates: `created`, `started`, `completed`, `failed`, `cancelled`, `retried`, `retry_ratio:rate5m`.
   - Failure & success ratios across `5m`, `30m`, `1h`, `6h`, `24h`, `7d`, `30d` (noting that `CANCELLED` workflows are user-initiated and excluded from reliability failure ratios).
   - Duration SLIs: `orynx:workflow:duration_avg_seconds:rate5m`, `orynx:workflow:duration_p95_seconds:rate5m`.
   - Budget & burn rates: `orynx:workflow:error_budget_remaining:ratio_30d`, `orynx:workflow:burn_rate:{5m,30m,1h,6h}`.
3. **`orynx_task_sli_rules`** (15 rules):
   - Target: `orynx:task:slo_target_success_ratio` (`0.99`).
   - Execution rates (`executed`, `completed`, `failed`), failure ratios (`5m`, `30m`, `1h`, `6h`, `30d`), success ratios (`5m`, `1h`, `30d`), `orynx:task:error_budget_remaining:ratio_30d`, and `orynx:task:burn_rate:{5m,1h}`.
4. **`orynx_infrastructure_sli_rules`** (6 rules):
   - `orynx:backend:availability_ratio`
   - `orynx:postgres:connection_utilization_ratio`
   - `orynx:kafka:consumer_lag:by_group_topic` & `orynx:kafka:consumer_lag:max`
   - `orynx:otel:export_failure_ratio:rate5m` & `orynx:otel:export_success_ratio:rate5m`
