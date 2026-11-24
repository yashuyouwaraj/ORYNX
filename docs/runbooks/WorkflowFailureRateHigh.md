# Runbook: WorkflowFailureRateHigh (`WorkflowFailureRateHigh`, `WorkflowRetryRateElevated`)

## 1. What the Alert Means
- **`WorkflowFailureRateHigh` (`critical`)**: More than 10% of terminal workflows (`orynx:workflow:failure_ratio:rate5m > 0.10`) have ended in `FAILED` over the last 5 minutes for at least 2 consecutive minutes.
- **`WorkflowRetryRateElevated` (`warning`)**: Workflow retry rate exceeds 20% of started workflows (`orynx:workflow:retry_ratio:rate5m > 0.20`) over the last 5 minutes for at least 3 minutes.

## 2. User / Business Impact
User-submitted and AI-generated workflows are failing to complete their DAG execution, directly degrading the **99.0% Workflow Completion Reliability SLO** and rapidly consuming the 30-day workflow error budget.

## 3. Initial Investigation Steps
1. Open the **OrynX SLO & Operational Reliability** and **OrynX Workflow Execution** dashboards in Grafana.
2. Check whether `TaskFailureRateHigh` is also firing — most workflow failures originate from task execution failures in `execution-service`.
3. Query container logs in `orchestrator-service` and `execution-service` for `"level":"ERROR"` or `"status":"FAILED"`, noting the `workflowId` and `trace_id` fields.
4. Open **Grafana Explore -> Tempo** and search by `trace_id` to pinpoint the exact failing span and exception.

## 4. Useful Commands
```bash
# Search orchestrator-service JSON logs for failed workflows and trace IDs
docker compose --profile observability logs --tail=200 orchestrator-service | grep -i "FAILED"

# Search execution-service JSON logs for task execution exceptions
docker compose --profile observability logs --tail=200 execution-service | grep -E '"level":"ERROR"|FAILED'

# Check planning-service logs if AI workflow planning is failing
docker compose --profile observability logs --tail=100 planning-service
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Workflow Success Rate SLI*, *Workflow Error Budget Remaining*, and *Multi-Window Error Budget Burn Rates*.
- **OrynX Workflow Execution** (`uid: orynx-workflow-execution`) — *Workflows Completed*, *Workflows Failed*, *Workflows Retried*, and *Tasks Executed by Status*.

## 6. Relevant Prometheus Queries
```promql
# Current 5m workflow failure ratio and success ratio
orynx:workflow:failure_ratio:rate5m
orynx:workflow:success_ratio:rate5m

# Workflow completion and failure rates per second
sum by (status) (rate(orynx_workflows_completed_total[5m]))

# Workflow retry ratio over 5m
orynx:workflow:retry_ratio:rate5m

# Remaining 30-day workflow error budget
orynx:workflow:error_budget_remaining:ratio_30d
```

## 7. Likely Root Causes
- **Downstream Task Executor Failure**: External HTTP dependencies, script/command tasks, or simulated task handlers in `execution-service` are throwing exceptions or timing out.
- **Invalid DAG / Task Configuration**: Malformed task payload or missing parameters submitted to `orchestrator-service`.
- **Database / Kafka Transient Errors**: Optimistic locking collisions, database deadlocks, or Kafka producer timeouts during state transitions.
- **Worker Thread Pool Saturation**: `execution-service` worker queue (`ORYNX_EXECUTION_WORKER_QUEUE_CAPACITY`) is rejecting tasks under burst load.

## 8. Safe Recovery Actions
1. If failures are caused by a transient downstream outage or database/Kafka glitch, resolve the infrastructure issue first.
2. If `execution-service` worker pool saturation is causing rejections, increase `ORYNX_EXECUTION_WORKER_MAX_POOL_SIZE` and `ORYNX_EXECUTION_WORKER_QUEUE_CAPACITY` in `.env` and restart `execution-service`:
   ```bash
   docker compose --profile observability up -d execution-service
   ```
3. Once the underlying cause is resolved, retry affected failed workflows via `PATCH /api/v1/workflows/{id}/retry` (with `Authorization: Bearer <jwt>`, or via the OrynX UI Retry button). Generation fencing in `orchestrator-service` guarantees old execution attempts cannot corrupt the retried run.

## 9. Escalation Criteria
- Escalate immediately to the **Workflow Orchestration On-Call Engineer** if `WorkflowSloFastBurnCritical` fires alongside `WorkflowFailureRateHigh` or if `orynx:workflow:error_budget_remaining:ratio_30d` drops below `0.25` (25%).
- Escalate to the **Database / Messaging Team** if logs show persistent SQL deadlocks or Kafka serialization errors.

## 10. Verification After Recovery
1. Run a test workflow execution and verify it reaches `COMPLETED`.
2. Confirm `orynx:workflow:success_ratio:rate5m` recovers above `0.99` (99.0%).
3. Confirm `WorkflowFailureRateHigh` and `WorkflowRetryRateElevated` resolve in Alertmanager.
