# Runbook: RedisCoordinationErrorsHigh

## 1. What the Alert Means
- **`RedisCoordinationErrorsHigh` (`warning`)**: The failure ratio on Redis coordination operations (`sum(rate(orynx_redis_operations_total{status="failure"}[5m])) / clamp_min(sum(rate(orynx_redis_operations_total[5m])), 0.1) > 0.05`) exceeds 5% for more than 2 minutes.
- Indicates communication failures, timeouts, connection drops, or script evaluation exceptions between `orchestrator-service` and the Redis cluster.

## 2. User / Business Impact
- If `orynx.admission.failure-policy=FAIL_CLOSED`: Clients attempting to start or resume workflows receive HTTP `503 Service Unavailable` with `Retry-After: 5`.
- If `orynx.admission.failure-policy=FAIL_OPEN`: Workflows continue to execute, but global and per-user concurrency limits are bypassed, exposing worker pools to potential saturation.

## 3. Initial Investigation Steps
1. Check Redis container health and status via `docker compose ps redis`.
2. Check `orchestrator-service` logs for Redis connection exceptions:
   ```bash
   docker compose logs --tail=100 orchestrator-service | grep -E "Redis|Lettuce|WorkflowAdmission"
   ```
3. Test Redis latency and connectivity from inside orchestrator container:
   ```bash
   docker compose exec orchestrator-service ping redis
   ```

## 4. Useful Commands
```bash
# Check Redis container status
docker compose ps redis

# Tail Redis container logs
docker compose logs --tail=100 redis

# Ping Redis directly using redis-cli
docker compose exec redis redis-cli ping

# Check Redis connection statistics
docker compose exec redis redis-cli info stats

# Inspect memory usage
docker compose exec redis redis-cli info memory
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Workflow Admission Control & Capacity* panel and *Redis Coordination Operation Rates*.
- **OrynX Infrastructure** (`uid: orynx-infrastructure`) — *Redis Up* and *Connected Clients*.

## 6. Relevant Prometheus Queries
```promql
# Rate of failed Redis coordination operations
rate(orynx_redis_operations_total{status="failure"}[5m])

# Failure ratio of Redis coordination operations
sum(rate(orynx_redis_operations_total{status="failure"}[5m])) / clamp_min(sum(rate(orynx_redis_operations_total[5m])), 0.1)

# Redis connectivity status
redis_up

# Connected clients to Redis
redis_connected_clients
```

## 7. Likely Root Causes
- **Redis OOM or Process Crash**: Redis hit maxmemory limit or OS OOM-killer terminated the process.
- **Network Partition or Connection Pool Starvation**: Network delays between orchestrator-service and Redis, or Lettuce connection timeouts.
- **Corrupted or Missing Lua Scripts**: Script caching eviction requiring reload of `admit_workflow.lua` or `release_workflow.lua`.

## 8. Safe Recovery Actions
1. **Restart Redis**: If Redis is unresponsive or deadlocked, restart the container:
   ```bash
   docker compose restart redis
   ```
2. **Verify Network Connectivity**: Check Docker network health and DNS resolution of `redis:6379`.
3. **Verify Reconciliation**: Once Redis recovers, observe `AdmissionReconciliationJob` in `orchestrator-service` logs to confirm it reconciles the admission state with PostgreSQL.

## 9. Escalation Criteria
- If Redis restarts repeatedly or logs corrupt AOF/RDB files, escalate to Database/Infra team.
- If failure rate remains > 5% for more than 15 minutes, consider switching `orynx.admission.failure-policy=FAIL_OPEN` temporarily if worker capacity is available.

## 10. Verification After Recovery
1. Verify `redis_up == 1` and `orynx_redis_operations_total{status="failure"}` drops to 0.
2. Confirm `AdmissionReconciliationJob` logs: `"Admission reconciliation completed"` without errors.
3. Verify test workflow start/admission succeeds with HTTP 200.
