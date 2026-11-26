# Runbook: Execution Worker Failures, Fencing & Recovery Stalls

## Symptoms
- Workflows remain in `RUNNING` longer than expected without task progress updates.
- Grafana **OrynX Resilience, Workers & Recovery** dashboard shows spikes in:
  - `orynx_execution_fenced_total` (stale worker fencing)
  - `orynx_worker_pool_rejected_total` (worker pool saturation)
  - `orynx_execution_retries_total`
- Loki logs for `execution-service` show `errorCategory="FENCING"` or `errorCategory="CAPACITY"`.

---

## 1. Triage & Diagnostics

### Check Service & Worker Pool Health
```bash
docker compose ps execution-service orchestrator-service
```
Query Prometheus (`http://127.0.0.1:9090`):
- Active workers: `sum(orynx_worker_pool_active)`
- Queue depth: `sum(orynx_worker_pool_queue_size)`
- Fencing rate: `rate(orynx_execution_fenced_total[5m])`
- Pool rejections: `rate(orynx_worker_pool_rejected_total[5m])`

### Correlate in Loki
```logql
{service_name="execution-service"} | errorCategory=~"FENCING|CAPACITY|TIMEOUT"
```
Inspect `workflowId`, `executionId`, `workerId`, `attemptNumber`, and `trace_id` structured metadata.

---

## 2. Automated Recovery Behavior
- `execution-service` maintains PostgreSQL-backed execution leases (`orynx.execution.worker.lease-duration-seconds=300`, heartbeat every `30s`).
- If a worker crashes or stalls, the recovery scanner (`recovery-interval-seconds=60`) detects expired leases and re-dispatches with an incremented `executionGeneration` up to `max-recovery-attempts=3`.
- Any late write from the old worker fails deterministically with `StaleWorkerExecutionException` (fenced) without corrupting state.

---

## 3. Mitigation & Manual Intervention
1. **Worker Pool Saturation (`CAPACITY`)**:
   - Check `ORYNX_EXECUTION_WORKER_CORE_POOL_SIZE`, `MAX_POOL_SIZE`, and `QUEUE_CAPACITY`, or tighten `ORYNX_ADMISSION_MAX_GLOBAL` in `orchestrator-service` to shed load cleanly at admission.
2. **Repeated Fencing (`FENCING`) or Memory Pressure**:
   - Inspect GC pauses and heap usage (`OrynX Backend Service Health` dashboard) and container RSS (`docker stats`).
   - `execution-service` defaults to `EXECUTION_SERVICE_MEM_LIMIT=768m` with `EXECUTION_SERVICE_JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0` (leaving ~40% for Metaspace, OTel Java agent, and native buffers). Increase `EXECUTION_SERVICE_MEM_LIMIT` if sustained concurrency pushes container RSS above 80%.
3. **Graceful Restart of `execution-service`**:
   ```bash
   docker compose restart execution-service
   ```
   - Docker sends `SIGTERM` and allows `stop_grace_period: 45s` before `SIGKILL`.
   - Spring initiates graceful shutdown (`spring.lifecycle.timeout-per-shutdown-phase=20s`) and `ThreadPoolTaskExecutor` drains in-flight worker tasks up to `orynx.execution.worker.await-termination-seconds=30`.
   - **Limitation**: Graceful restart does not guarantee completion of tasks that exceed the `30s` drain timeout (or `45s` container grace period). Any execution interrupted before completion is recovered automatically after its PostgreSQL lease expires (`300s`), with generation fencing rejecting stale writes.
4. **Manual Retry / Cancel (when `max-recovery-attempts` exhausted)**:
   - Use `PATCH /api/v1/workflows/{id}/retry` or `PATCH /api/v1/workflows/{id}/cancel` via the API Gateway. Never manually edit `workflow_executions` rows in PostgreSQL unless all workers for that workflow are stopped and verified idle.
