# Runbook: ResilienceExperimentCleanupFailed

## 1. What the Alert Means
This alert indicates that a resilience failure experiment executed by the `ResilienceExperimentEngine` in `orchestrator-service` completed its failure injection and observation phases, but the automated cleanup / teardown routine failed to restore the target component to its normal baseline operational state. As a result, the experiment state machine transitioned to `CLEANUP_FAILED`, and the engine activated **QUARANTINE MODE** locally and in Redis (`orynx:{admission}:resilience_quarantine`). All subsequent resilience experiments are strictly blocked until an operator completes investigation and clears the quarantine.

## 2. User / Business Impact
- The resilience execution engine is locked in quarantine mode; any attempt to run a new experiment will be rejected with a `SafetyValidationException`.
- The distributed coordination lock `orynx:{admission}:resilience_lock` remains held or fenced to prevent adjacent nodes from initiating experiments.
- If in-process faults in `ResilienceFaultRegistry` were not deactivated, targeted application adapters might still exhibit degraded behavior until manually cleared.
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
4. Inspect the Redis quarantine key:
   ```bash
   docker exec -it orynx-audit-redis redis-cli get "orynx:{admission}:resilience_quarantine"
   ```
5. Check if the target component (e.g. Redis, Kafka, PostgreSQL, worker) is accessible and operational.

## 4. Useful Commands
- Check active orchestrator logs for resilience events:
   ```bash
   docker logs orynx-orchestrator-1 | grep -i "\[RESILIENCE\]"
   ```
- Inspect Redis admission and quarantine keys:
   ```bash
   docker exec -it orynx-audit-redis redis-cli keys "orynx:{admission}:*"
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
1. **Target resource unreachable during cleanup**: The adapter attempted to contact the target resource to reset state, but the connection timed out.
2. **Target container/process crashed or terminated unexpectedly**: The isolated container or process was killed before cleanup could finish.
3. **In-process fault registry not cleared**: An unexpected exception occurred inside the adapter's `cleanup()` implementation before invoking `faultRegistry.deactivateFault()`.
4. **Permissions or locking deadlock**: An active lock on the target prevented configuration reversion.
5. **Redis Connectivity Failure (Quarantine Fail-Closed)**: If Redis is offline or experiencing network partition, the engine's quarantine verification automatically fails closed by raising `SafetyValidationException`. This prevents experiments from running when cluster quarantine status cannot be reliably determined.

## 8. Safe Recovery & Quarantine Reset Actions
1. **Verify Redis Connectivity**:
   - Confirm Redis is reachable: `redis-cli ping`.
   - If Redis was down or partitioned, restoring connectivity may clear the fail-closed condition once Redis replies.
2. **Manual Component State Reset**:
   - For Redis: Ensure `ResilienceFaultRegistry` has deactivated any `REDIS_INTERRUPTION` faults and `RedisCoordinationProperties` retains expected production settings.
   - For Kafka: Re-enable producer dispatch and unpause consumer groups.
   - For PostgreSQL: Terminate hanging transactions or reset connection pool.
   - For Worker: Remove stale lease keys from Redis and restart worker process if necessary.
   - For Network Latency: Clear any simulated proxy delays or traffic shaping rules.
3. **Clear Quarantine via Operator Reset**:
   - Once all components are verified healthy, invoke `clearQuarantine`:
     ```java
     // Programmatic invocation or via admin actuator endpoint
     resilienceExperimentEngine.clearQuarantine("Manual operator recovery verification by SRE");
     ```
   - Alternatively, remove the Redis quarantine flag and release the cluster lock:
     ```bash
     docker exec -it orynx-audit-redis redis-cli del "orynx:{admission}:resilience_quarantine"
     docker exec -it orynx-audit-redis redis-cli del "orynx:{admission}:resilience_lock"
     ```
   - Note: If Redis is down, `clearQuarantine` will fail loudly with `IllegalStateException` rather than silently succeeding.
4. **Verify Environment Isolation**:
   - Confirm that no production or shared resources were affected.

## 9. Escalation Criteria
- Escalate to the Reliability Engineering / SRE On-Call lead if cleanup failure occurred in a shared or staging environment and cannot be manually remediated within 15 minutes.
- Escalate if workflow executions across unrelated tenants are failing with 503 errors following an experiment run.

## 10. Verification After Recovery
1. Verify that `resilienceExperimentEngine.isQuarantined()` returns `false`.
2. Verify that `orynx_resilience_active_experiments` returns `0`.
3. Verify normal workflow execution by submitting a test workflow through the gateway:
   ```bash
   curl -X POST http://localhost:8080/api/v1/workflows \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <TOKEN>" \
     -d '{"name": "post-resilience-smoke", "tasks": [{"name": "task-1", "action": "echo"}]}'
   ```
4. Confirm workflow completes with status `COMPLETED`.
5. Inspect orchestrator logs for zero continuous cleanup errors.
