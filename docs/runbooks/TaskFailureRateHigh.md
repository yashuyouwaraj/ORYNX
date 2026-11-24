# Runbook: TaskFailureRateHigh (`TaskFailureRateHigh`, `TaskExecutionStalled`)

## 1. What the Alert Means
- **`TaskFailureRateHigh` (`critical`)**: More than 10% of executed workflow DAG tasks (`orynx:task:failure_ratio:rate5m > 0.10`) have completed with `status="FAILED"` over the last 5 minutes for at least 2 minutes.
- **`TaskExecutionStalled` (`warning`)**: Workflows are starting (`orynx:workflow:started:rate5m > 0`), but zero task completions (`orynx:task:executed:rate5m == 0`) have been recorded for 5 consecutive minutes.

## 2. User / Business Impact
Individual DAG tasks inside running workflows are failing or stalling in `execution-service`, causing task-level retries, delaying workflow progress, and breaching the **99.0% Task Execution Reliability SLO**.

## 3. Initial Investigation Steps
1. Check the **OrynX Workflow Execution** dashboard (*Tasks Executed by Status* panel) to observe the spike in `FAILED` tasks or drop in task throughput.
2. Inspect `execution-service` structured JSON logs for task execution stack traces, noting `workflowId`, `trace_id`, and `span_id`.
3. Inspect `orchestrator-service` logs around `ExecutionEventConsumer` to verify `task-completed` Kafka events are being consumed and processed without errors.
4. Check `KafkaConsumerLagHigh` to determine if `workflow-execution` or `task-completed` topics have backed-up messages.

## 4. Useful Commands
```bash
# Tail execution-service logs for task failures
docker compose --profile observability logs --tail=200 execution-service | grep -E '"level":"ERROR"|Task'

# Tail orchestrator-service ExecutionEventConsumer logs
docker compose --profile observability logs --tail=150 orchestrator-service | grep -i "ExecutionEventConsumer"

# Verify execution-service health and metrics endpoint
docker compose --profile observability ps execution-service
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Task Execution Success SLI (5m vs 99.0% SLO)* and *Task Error Budget Remaining (30d)*.
- **OrynX Workflow Execution** (`uid: orynx-workflow-execution`) — *Tasks Executed by Status (Total & Rate)*.

## 6. Relevant Prometheus Queries
```promql
# 5-minute task failure ratio and success ratio
orynx:task:failure_ratio:rate5m
orynx:task:success_ratio:rate5m

# Task execution rate by status (COMPLETED vs FAILED)
sum by (status) (rate(orynx_tasks_executed_total[5m]))

# Task SLO burn rates (5m and 1h)
orynx:task:burn_rate:5m
orynx:task:burn_rate:1h
```

## 7. Likely Root Causes
- **Downstream Endpoint / Action Failure**: External service called by task handlers is returning HTTP 5xx or timing out.
- **Kafka Connectivity / Consumer Stall**: `execution-service` lost connection to `kafka:9092` or consumer thread stalled (`TaskExecutionStalled`).
- **Database / Redis Contention**: `execution-service` cannot acquire or renew execution leases or persist intermediate task state.
- **Invalid Task Definition**: Unsupported task action type or invalid task configuration payload.

## 8. Safe Recovery Actions
1. If `TaskExecutionStalled` is firing due to a stuck consumer or worker pool deadlock in `execution-service`, restart `execution-service`:
   ```bash
   docker compose --profile observability restart execution-service
   ```
2. Verify `kafka` and `postgres` containers are healthy (`docker compose --profile observability ps kafka postgres`).
3. After recovery, retry affected failed workflows via `POST /api/workflows/{id}/retry`.

## 9. Escalation Criteria
- Escalate to the **Execution Engine On-Call Engineer** if `TaskSloFastBurnCritical` fires (`burn_rate > 14.4x`) or if restarting `execution-service` does not resume task execution within 3 minutes.

## 10. Verification After Recovery
1. Confirm `orynx:task:executed:rate5m > 0` when workflows are running and `orynx:task:success_ratio:rate5m >= 0.99`.
2. Verify `TaskFailureRateHigh` and `TaskExecutionStalled` clear in Alertmanager.
