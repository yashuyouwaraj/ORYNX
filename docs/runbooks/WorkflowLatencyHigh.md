# Runbook: WorkflowLatencyHigh (`WorkflowExecutionLatencyHigh`, `WorkflowCompletionDegraded`)

## 1. What the Alert Means
- **`WorkflowExecutionLatencyHigh` (`warning`)**: The 95th percentile end-to-end workflow execution duration over the last 5 minutes exceeds the **30.0s SLO target** (`orynx:workflow:duration_p95_seconds:rate5m > 30`) for at least 3 minutes.
- **`WorkflowCompletionDegraded` (`warning`)**: Workflows have started over the last 5 minutes (`orynx:workflow:started:rate5m > 0`), but zero workflows have reached `COMPLETED` or `FAILED` for 5 consecutive minutes.

## 2. User / Business Impact
Workflows take significantly longer than expected to finish or appear stuck in `RUNNING` / `PLANNING` state in the UI, degrading interactive user experience and risking downstream timeouts.

## 3. Initial Investigation Steps
1. Check whether `KafkaConsumerLagHigh` or `TaskExecutionStalled` is also firing.
2. Inspect the **OrynX Infrastructure & Messaging** dashboard for Kafka consumer lag on `workflow-execution`, `task-started`, `task-completed`, and `workflow-completed`.
3. Inspect the **OrynX System Overview** dashboard for CPU throttling, JVM GC pauses (`jvm_gc_pause_seconds`), or high PostgreSQL connection utilization.
4. Sample a slow workflow trace in **Grafana Explore -> Tempo** (filter by duration `> 10s`) to identify whether time is spent in Kafka queueing, database locks, AI planning (`planning-service`), or task execution (`execution-service`).

## 4. Useful Commands
```bash
# Check container CPU and memory utilization in real time
docker stats --no-stream

# Inspect orchestrator-service and execution-service logs for slow queries or timeouts
docker compose --profile observability logs --tail=150 orchestrator-service
docker compose --profile observability logs --tail=150 execution-service
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Latency Percentiles vs SLO Targets* and *Max Kafka Consumer Group Lag*.
- **OrynX Workflow Execution** (`uid: orynx-workflow-execution`) — *Workflow Execution Duration (Avg & Max)* and *Workflow Completion Throughput*.
- **OrynX Infrastructure & Messaging** (`uid: orynx-infrastructure`) — *Kafka Consumer Group Lag by Topic* and *PostgreSQL Active Connections*.

## 6. Relevant Prometheus Queries
```promql
# Workflow P95 and average execution duration over 5m
orynx:workflow:duration_p95_seconds:rate5m
orynx:workflow:duration_avg_seconds:rate5m

# Started vs completed workflow rate
orynx:workflow:started:rate5m
orynx:workflow:completed:rate5m + orynx:workflow:failed:rate5m

# Kafka consumer lag by group and topic
orynx:kafka:consumer_lag:by_group_topic

# JVM GC pause time rate
rate(jvm_gc_pause_seconds_sum[5m])
```

## 7. Likely Root Causes
- **Kafka Consumer Lag**: `execution-service` or `orchestrator-service` consumers are lagging behind burst workflow creation.
- **Worker Pool Contention**: Deep DAG workflows with high parallelism are queueing on `execution-service` worker threads (`ORYNX_EXECUTION_WORKER_CORE_POOL_SIZE`).
- **Database Lock Contention / Connection Starvation**: Slow PostgreSQL queries or exhausted HikariCP connection pools delaying state transitions.
- **Slow External AI / HTTP Calls**: NVIDIA NIM API latency during AI planning or slow external task endpoints.

## 8. Safe Recovery Actions
1. If Kafka consumer lag is growing on `workflow-execution`, check `execution-service` health and increase worker concurrency (`ORYNX_EXECUTION_WORKER_CORE_POOL_SIZE` / `ORYNX_EXECUTION_WORKER_MAX_POOL_SIZE` in `.env`), then recreate `execution-service`:
   ```bash
   docker compose --profile observability up -d execution-service
   ```
2. If a specific runaway workflow is monopolizing worker threads, pause or cancel it via `POST /api/workflows/{id}/pause` or `POST /api/workflows/{id}/cancel`.
3. If PostgreSQL connection utilization is high (`> 80%`), check for long-running idle-in-transaction sessions in Postgres and terminate stalled queries.

## 9. Escalation Criteria
- Escalate to the **Workflow Orchestration On-Call Engineer** if `WorkflowCompletionDegraded` persists for more than 10 minutes (zero workflows completing) or if P95 duration exceeds `120s`.

## 10. Verification After Recovery
1. Verify `orynx:workflow:duration_p95_seconds:rate5m < 30` in Prometheus.
2. Verify `orynx:kafka:consumer_lag:max` is draining toward `0`.
3. Confirm `WorkflowExecutionLatencyHigh` / `WorkflowCompletionDegraded` resolves in Alertmanager.
