# Runbook: SLOBurnRateHigh (`ApiSloFastBurnCritical`, `ApiSloSlowBurnWarning`, `WorkflowSloFastBurnCritical`, `WorkflowSloSlowBurnWarning`, `TaskSloFastBurnCritical`)

## 1. What the Alert Means
OrynX uses Google SRE multi-window, multi-burn-rate alerts to detect both rapid and gradual consumption of the 30-day error budget:
- **Fast Burn (`critical`, `> 14.4x` over both `1h` and `5m` windows for `2m`)**: The service is consuming its 30-day error budget 14.4 times faster than sustainable (2% of the 30-day budget burned in 1 hour; total budget exhaustion in ~50 hours).
  - `ApiSloFastBurnCritical`: API 5xx error ratio exceeds `1.44%` (vs `99.9%` availability target / `0.1%` budget).
  - `WorkflowSloFastBurnCritical`: Workflow failure ratio exceeds `14.4%` (vs `99.0%` reliability target / `1.0%` budget).
  - `TaskSloFastBurnCritical`: Task failure ratio exceeds `14.4%` (vs `99.0%` reliability target / `1.0%` budget).
- **Slow Burn (`warning`, `> 6.0x` over both `6h` and `30m` windows for `15m`)**: The service is consuming its 30-day error budget 6 times faster than sustainable (5% of the 30-day budget burned in 6 hours; total budget exhaustion in 5 days).
  - `ApiSloSlowBurnWarning`: API 5xx error ratio exceeds `0.6%` across `6h` and `30m`.
  - `WorkflowSloSlowBurnWarning`: Workflow failure ratio exceeds `6.0%` across `6h` and `30m`.

## 2. User / Business Impact
Users are actively experiencing elevated HTTP 5xx responses, failed workflow executions, or failed DAG tasks at a rate that will breach OrynX's monthly reliability commitment if not mitigated promptly.

## 3. Initial Investigation Steps
1. Open the **OrynX SLO & Operational Reliability** dashboard (`uid: orynx-slo-reliability`) in Grafana.
2. Identify which domain is burning budget (`orynx-api`, `orchestrator-service` workflows, or `execution-service` tasks) from the `service` label.
3. For **API burn (`orynx-api`)**, query `orynx:api:errors:rate5m_by_service` to identify which microservice (`gateway-service`, `auth-service`, `orchestrator-service`, `planning-service`, `execution-service`) is emitting `outcome="SERVER_ERROR"`.
4. For **Workflow / Task burn**, check `WorkflowFailureRateHigh.md` and `TaskFailureRateHigh.md`, inspect structured JSON error logs, and correlate `trace_id` in Grafana Tempo.

## 4. Useful Commands
```bash
# Check health of all containers
docker compose --profile observability ps

# Search all backend services for recent ERROR logs
docker compose --profile observability logs --tail=150 gateway-service auth-service orchestrator-service planning-service execution-service | grep '"level":"ERROR"'
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Multi-Window Error Budget Burn Rates*, *API/Workflow/Task Availability SLIs*, and *Error Budget Remaining Gauges*.
- **OrynX Platform Overview** (`uid: orynx-platform-overview`) — *Application HTTP 5xx Error Rate* and *HTTP Request Rate*.
- **OrynX Workflow Execution** (`uid: orynx-workflow-execution`) — *Workflows Failed* and *Tasks Executed by Status*.

## 6. Relevant Prometheus Queries
```promql
# API burn rates (5m, 30m, 1h, 6h) and per-service 5xx rates
orynx:api:burn_rate:5m
orynx:api:burn_rate:1h
orynx:api:burn_rate:6h
orynx:api:errors:rate5m_by_service

# Workflow burn rates (5m, 30m, 1h, 6h)
orynx:workflow:burn_rate:5m
orynx:workflow:burn_rate:1h
orynx:workflow:burn_rate:6h

# Task burn rates (5m, 1h)
orynx:task:burn_rate:5m
orynx:task:burn_rate:1h
```

## 7. Likely Root Causes
- **Recent Defective Deployment / Config Change**: A newly deployed service change or invalid `.env` setting is causing HTTP 500s or task exceptions.
- **Partial Dependency Degradation**: Slow/intermittent database, Redis, Kafka, or NVIDIA NIM API responses causing request/task timeouts.
- **Resource Saturation**: Connection pool exhaustion, thread pool rejection, or JVM heap pressure under traffic spikes.

## 8. Safe Recovery Actions
1. If the burn rate spike correlates with a recent deployment or configuration change, roll back the change immediately.
2. If a specific microservice is degraded due to resource exhaustion or hung connections, restart that service:
   ```bash
   docker compose --profile observability restart <service_name>
   ```
3. Follow the domain-specific runbook (`ServiceUnavailable.md`, `WorkflowFailureRateHigh.md`, `TaskFailureRateHigh.md`, `PostgreSQLUnavailable.md`, or `KafkaUnavailable.md`) to remediate the root failure.

## 9. Escalation Criteria
- **Fast Burn (`critical`)**: Immediate page to the **Primary On-Call Engineer**; escalate to **Engineering Lead** if the 5-minute burn rate (`*:burn_rate:5m`) is not brought below `1.0` within 15 minutes.
- **Slow Burn (`warning`)**: Investigate within 1 hour; escalate ifremaining error budget drops below `25%`.

## 10. Verification After Recovery
1. Verify the short-window burn rate (`orynx:api:burn_rate:5m`, `orynx:workflow:burn_rate:5m`, or `orynx:task:burn_rate:5m`) drops below `1.0`.
2. Because multi-window alerts require *both* the short (`5m`/`30m`) and long (`1h`/`6h`) windows to exceed the threshold, the alert automatically resolves as soon as the short window recovers below the threshold!
3. Confirm the burn alert resolves in Alertmanager.
