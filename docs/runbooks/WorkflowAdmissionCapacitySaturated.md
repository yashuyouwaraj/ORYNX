# Runbook: WorkflowAdmissionCapacitySaturated

## 1. What the Alert Means
- **`WorkflowAdmissionCapacitySaturated` (`warning`)**: Global admission utilization (`orynx_admission_utilization`) has exceeded 90% (0.90) for more than 3 minutes.
- Indicates that the cluster is operating near maximum concurrent workflow execution capacity (`orynx.admission.max-global-concurrent-workflows`).

## 2. User / Business Impact
- Client requests to start, resume, or retry workflows may begin receiving HTTP `429 Too Many Requests` responses with a `Retry-After` header.
- Scheduled workflows may be deferred to the next polling cycle.
- Background throughput may degrade as new work is queued or throttled.

## 3. Initial Investigation Steps
1. Open the **OrynX SLO & Operational Reliability** dashboard (`uid: orynx-slo-reliability`) and inspect the *Workflow Admission Control & Capacity* panel.
2. Check Prometheus for active workflow counts:
   ```promql
   orynx_admission_active_workflows
   orynx_admission_utilization
   sum by (reason) (increase(orynx_workflow_admission_rejected_total[15m]))
   ```
3. Inspect `orchestrator-service` logs for admission throttling messages:
   ```bash
   docker compose logs --tail=100 orchestrator-service | grep "Workflow .* admission rejected"
   ```
4. Query PostgreSQL to verify whether active workflows in Redis match genuinely running workflows in PostgreSQL:
   ```sql
   SELECT status, COUNT(*) FROM workflows GROUP BY status;
   ```

## 4. Useful Commands
```bash
# Inspect current active workflows in Redis directly
docker compose exec redis redis-cli scard "orynx:{admission}:active_workflows"

# Inspect user-specific active sets
docker compose exec redis redis-cli keys "orynx:{admission}:user_workflows:*"

# Check execution worker utilization in execution-service
docker compose logs --tail=50 execution-service | grep "activeExecutionTracker"
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Workflow Admission Control & Capacity* panel and *Active Executions*.
- **OrynX Workflow Execution** (`uid: orynx-workflows`) — *Workflow Start Rate* and *Concurrent Running Workflows*.

## 6. Relevant Prometheus Queries
```promql
# Active admitted workflows
orynx_admission_active_workflows

# Capacity utilization ratio
orynx_admission_utilization

# Rate of rejected admissions by reason
sum by (reason) (rate(orynx_workflow_admission_rejected_total[5m]))
```

## 7. Likely Root Causes
- **Sudden Traffic Spike / Bulk Job Ingestion**: A user or automated script submitted a large batch of workflows simultaneously.
- **Orphaned / Stale Leases in Redis**: Workflows reached terminal state in PostgreSQL but the completion event was dropped or missed by Redis release.
- **Hung Executions in Worker Nodes**: Workflows are stuck waiting on external API calls or prolonged tasks without timing out.

## 8. Safe Recovery Actions
1. **Dynamic Capacity Adjustment**: If worker nodes (CPU/memory) have headroom, increase `ORYNX_ADMISSION_MAX_GLOBAL_CONCURRENT_WORKFLOWS` dynamically or scale `execution-service` replicas.
2. **Reconciliation Inspection**: The `AdmissionReconciliationJob` runs periodically (default: every 60s) under leader lock and cleans stale slots automatically. To trigger manual reconciliation immediately, inspect logs or restart orchestrator.
3. **Terminate Hung Workflows**: Inspect execution logs, check for hung tasks, and cancel dead workflows if necessary.

## 9. Escalation Criteria
- If capacity utilization remains at 100% for > 15 minutes and tasks are not progressing, escalate to Orchestrator engineering.
- If worker memory/CPU is exhausted, trigger horizontal pod/container scaling for `execution-service`.

## 10. Verification After Recovery
1. Verify `orynx_admission_utilization` drops below 0.80.
2. Confirm new workflow execution requests succeed with HTTP 200 without `429 Too Many Requests`.
3. Check that `AdmissionReconciliationJob` runs cleanly with 0 errors.
