# Runbook: ResilienceExperimentCleanupFailed

## 1. What the Alert Means
This alert indicates that a resilience failure experiment executed by the `ResilienceExperimentEngine` in `orchestrator-service` completed its failure injection and observation phases, but the automated cleanup / teardown routine failed to restore the target component to its normal baseline operational state. As a result, the experiment state machine transitioned to `CLEANUP_FAILED`, and the target environment may still contain artificial latency, fault proxies, or disrupted connection configurations.

## 2. User / Business Impact
- In isolated test environments, subsequent test runs or benchmarks may encounter unexpected degradation or errors.
- If an uncontained experiment was executed, simulated faults could leak into adjacent test pipelines or cluster components.
- Platform reliability assertions become invalid until the environment is returned to baseline.

## 3. Initial Investigation Steps
1. Identify the failed experiment ID and scenario type from the orchestrator structured logs:
   ```bash
   grep -E "\[RESILIENCE\] Cleanup failed" /var/log/orynx/orchestrator-service.log
   ```
2. Query Prometheus for the cleanup failure metric to identify the scenario:
   ```promql
   sum by (scenario) (orynx_resilience_cleanup_failures_total)
   ```
3. Inspect the `ExperimentResult` recorded in the `ExperimentRegistry` to retrieve the `verificationErrors` and `targetResource`.
4. Check if the target component (e.g. Redis, Kafka, PostgreSQL, worker) is accessible and operational.

## 4. Useful Commands
- Check active orchestrator logs for resilience events:
  ```bash
  docker logs orynx-orchestrator-1 | grep -i "\[RESILIENCE\]"
  ```
- Inspect Redis admission keys to ensure no stale simulation flags exist:
  ```bash
  docker exec -it orynx-audit-redis redis-cli ping
  ```
- Verify Kafka broker health:
  ```bash
  docker exec -it orynx-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
  ```
- Check PostgreSQL connection status:
  ```bash
  docker exec -it orynx-audit-postgres pg_isready -U testuser -d orynx_test -p 5439
  ```

## 5. Relevant Grafana Dashboards
- **OrynX Platform Overview**: `monitoring/grafana/dashboards/orynx-platform-overview.json`
- **OrynX Infrastructure**: `monitoring/grafana/dashboards/orynx-infrastructure.json`
- **OrynX SLO & Reliability**: `monitoring/grafana/dashboards/orynx-slo-reliability.json`

## 6. Relevant Prometheus Queries
- Active running experiments:
  ```promql
  orynx_resilience_active_experiments
  ```
- Total cleanup failures by scenario:
  ```promql
  sum by (scenario) (orynx_resilience_cleanup_failures_total)
  ```
- Rate of completed experiments:
  ```promql
  sum by (scenario, outcome) (rate(orynx_resilience_experiments_total[5m]))
  ```

## 7. Likely Root Causes
1. **Network partition or timeout during teardown**: The adapter attempted to contact the target resource to reset state, but the connection timed out.
2. **Target resource crashed or terminated unexpectedly**: The isolated container or process was killed before cleanup could finish.
3. **Permissions or locking deadlock**: An active lock on the target prevented configuration reversion.
4. **Adapter bug or unhandled exception**: An unexpected exception occurred inside the adapter's `cleanup()` implementation.

## 8. Safe Recovery Actions
1. **Manual Component State Reset**:
   - For Redis: Ensure `AdmissionFailurePolicy` is reset to `FAIL_CLOSED` in application properties or coordination config.
   - For Kafka: Re-enable producer dispatch and unpause consumer groups.
   - For PostgreSQL: Terminate hanging transactions or reset connection pools.
   - For Worker: Remove stale lease keys from Redis and restart worker process if necessary.
   - For Network Latency: Clear any simulated proxy delays or traffic shaping rules.
2. **Clear In-Flight Experiment State**:
   - Call `ResilienceExperimentEngine.abort(experimentId)` to cancel remaining threads.
   - If the engine remains stuck in `CLEANUP_FAILED`, restart the orchestrator instance to clear the in-memory active experiment reference.
3. **Verify Environment Isolation**:
   - Confirm that no production or shared resources were affected.

## 9. Escalation Criteria
- Escalate to the Reliability Engineering / SRE On-Call lead if cleanup failure occurred in a shared or staging environment and cannot be manually remediated within 15 minutes.
- Escalate if workflow executions across unrelated tenants are failing with 503 errors following an experiment run.

## 10. Verification After Recovery
1. Verify that `orynx_resilience_active_experiments` returns `0`.
2. Verify normal workflow execution by submitting a test workflow through the gateway:
   ```bash
   curl -X POST http://localhost:8080/api/v1/workflows \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <TOKEN>" \
     -d '{"name": "post-resilience-smoke", "tasks": [{"name": "task-1", "action": "echo"}]}'
   ```
3. Confirm workflow completes with status `COMPLETED`.
4. Inspect orchestrator logs for zero continuous cleanup errors.
