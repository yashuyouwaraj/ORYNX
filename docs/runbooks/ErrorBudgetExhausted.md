# Runbook: ErrorBudgetExhausted (`ErrorBudgetExhausted`)

## 1. What the Alert Means
At least one of OrynX's 30-day rolling SLO error budgets has reached `0%` (`<= 0` for 5 minutes):
- **API Availability Error Budget**: `orynx:api:error_budget_remaining:ratio_30d <= 0` (30-day 5xx error ratio exceeded `0.1%`, dropping availability below `99.9%`).
- **Workflow Completion Reliability Error Budget**: `orynx:workflow:error_budget_remaining:ratio_30d <= 0` (30-day workflow failure ratio exceeded `1.0%`, dropping reliability below `99.0%`).
- **Task Execution Reliability Error Budget**: `orynx:task:error_budget_remaining:ratio_30d <= 0` (30-day task failure ratio exceeded `1.0%`, dropping task success below `99.0%`).

## 2. User / Business Impact
OrynX has breached its 30-day rolling Service-Level Objective for the affected reliability domain. Per SRE error budget policy, non-essential feature velocity must pause in favor of reliability remediation, post-incident review, and architectural hardening.

## 3. Initial Investigation Steps
1. Open the **OrynX SLO & Operational Reliability** dashboard (`uid: orynx-slo-reliability`) and inspect the three *Error Budget Remaining (30d)* gauges.
2. Check if an active outage or fast-burn incident (`ApiSloFastBurnCritical`, `WorkflowSloFastBurnCritical`, `TaskSloFastBurnCritical`, `BackendServiceDown`) is currently ongoing.
3. Review the 30-day SLI trend (`orynx:api:availability:ratio_rate30d`, `orynx:workflow:success_ratio:rate30d`, `orynx:task:success_ratio:rate30d`) to determine whether the budget was exhausted by a single severe outage or chronic low-grade failures.

## 4. Useful Commands
```bash
# Verify current container health across the stack
docker compose --profile observability ps

# Query Prometheus for the 30-day error budget remaining ratios
docker compose --profile observability exec prometheus wget -qO- "http://127.0.0.1:9090/api/v1/query?query=orynx:api:error_budget_remaining:ratio_30d"
docker compose --profile observability exec prometheus wget -qO- "http://127.0.0.1:9090/api/v1/query?query=orynx:workflow:error_budget_remaining:ratio_30d"
docker compose --profile observability exec prometheus wget -qO- "http://127.0.0.1:9090/api/v1/query?query=orynx:task:error_budget_remaining:ratio_30d"
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *API Error Budget Remaining (30d)*, *Workflow Error Budget Remaining (30d)*, *Task Error Budget Remaining (30d)*, and *Multi-Window Error Budget Burn Rates*.

## 6. Relevant Prometheus Queries
```promql
# Check which 30-day error budget is exhausted (0 = exhausted)
orynx:api:error_budget_remaining:ratio_30d
orynx:workflow:error_budget_remaining:ratio_30d
orynx:task:error_budget_remaining:ratio_30d

# Inspect 30-day rolling availability / success ratios
orynx:api:availability:ratio_rate30d
orynx:workflow:success_ratio:rate30d
orynx:task:success_ratio:rate30d

# Inspect current 5-minute burn rates to verify if degradation is still active
orynx:api:burn_rate:5m
orynx:workflow:burn_rate:5m
orynx:task:burn_rate:5m
```

## 7. Likely Root Causes
- **Major Unmitigated Outage**: A prolonged backend or database/Kafka outage consumed >100% of the monthly budget in a single incident.
- **Unaddressed Slow Burn**: Persistent `warning`-level slow burn (`> 6x`) or recurring task failures went unfixed over several days.
- **Unrealistic Downstream Dependency Reliability**: External task targets or LLM planning endpoints failing without sufficient retry/fallback isolation.

## 8. Safe Recovery Actions
1. **Immediate Incident Mitigation**: If `*:burn_rate:5m > 1`, resolve the active incident first using `SLOBurnRateHigh.md`, `WorkflowFailureRateHigh.md`, or `ServiceUnavailable.md`.
2. **Enforce Error Budget Policy**:
   - Institute a temporary feature freeze on the affected service (`gateway-service`, `orchestrator-service`, or `execution-service`).
   - Prioritize bug fixes, retry/circuit-breaker tuning, and regression tests until the rolling 30-day SLI climbs back above the SLO threshold (`99.9%` for API, `99.0%` for Workflow/Task).
3. **Conduct Blameless Postmortem**: Document the top error contributors over the 30-day window and create tracked remediation action items.

## 9. Escalation Criteria
- Always notify the **Platform Engineering Lead** and **Product Owner** when `ErrorBudgetExhausted` fires so error-budget governance and release prioritization can be enforced.

## 10. Verification After Recovery
1. Confirm short-term burn rates (`orynx:api:burn_rate:5m`, `orynx:workflow:burn_rate:5m`, `orynx:task:burn_rate:5m`) are `< 1.0` (ideally `0`).
2. Confirm `orynx:*:error_budget_remaining:ratio_30d > 0` as successful requests/workflows replenish the rolling 30-day ratio and `ErrorBudgetExhausted` resolves in Alertmanager.
