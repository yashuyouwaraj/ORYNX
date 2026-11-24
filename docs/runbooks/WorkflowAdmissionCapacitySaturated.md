# Runbook: WorkflowAdmissionCapacitySaturated

## 1. What the Alert Means
- **`WorkflowAdmissionCapacitySaturated` (`warning`)**: Global admission utilization (`orynx_workflow_admission_utilization`) has exceeded 90% (0.90) for more than 5 minutes.
- Indicates that the cluster is operating near maximum concurrent workflow execution capacity (`orynx.admission.global-max-concurrent-workflows`).

## 2. User / Business Impact
- Client requests to start, resume, or retry workflows may begin receiving HTTP `429 Too Many Requests` responses with a `Retry-After` header.
- Scheduled workflows may be deferred to the next polling cycle.
- Background throughput may degrade as new work is queued or throttled.

## 3. Initial Investigation Steps
1. Open the **OrynX SLO & Operational Reliability** dashboard (`uid: orynx-slo-reliability`) and inspect the *Workflow Admission Control & Capacity* panel.
2. Check Prometheus for active workflow counts:
   ```promql
   orynx_workflow_admission_active_workflows
   orynx_workflow_admission_utilization
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
docker compose exec redis redis-cli scard orynx:admission:active

# Inspect user-specific active sets
docker compose exec redis redis-cli keys "orynx:admission:user:*"

# Check execution worker utilization in execution-service
docker compose logs --tail=50 execution-service | grep "activeExecutionTracker"
```

## 5. Likely Root Causes & Remediation
- **Sudden Traffic Spike / Bulk Job Ingestion**: A user or automated script submitted a large batch of workflows simultaneously.
  - *Remediation*: If worker nodes (CPU/memory) have headroom, increase `ORYNX_ADMISSION_GLOBAL_MAX_CONCURRENT_WORKFLOWS` dynamically or scale `execution-service` replicas.
- **Orphaned / Stale Leases in Redis**: Workflows reached terminal state in PostgreSQL but the completion event was dropped or missed by Redis release.
  - *Remediation*: The `AdmissionReconciliationJob` runs every 5 minutes and cleans stale slots automatically. To trigger manual reconciliation immediately, check orchestrator logs or restart orchestrator.
- **Hung Executions in Worker Nodes**: Workflows are stuck waiting on external API calls or prolonged tasks without timing out.
  - *Remediation*: Inspect execution logs, check for thread deadlocks or task timeouts.
