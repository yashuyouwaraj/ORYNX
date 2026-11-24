# Runbook: RedisCoordinationErrorsHigh

## 1. What the Alert Means
- **`RedisCoordinationErrorsHigh` (`warning`)**: The rate of Redis coordination or admission control errors (`increase(orynx_workflow_admission_errors_total[5m]) > 0`) is elevated.
- Indicates communication failures, timeouts, connection drops, or script evaluation exceptions between `orchestrator-service` and the Redis cluster.

## 2. User / Business Impact
- If `orynx.admission.failure-policy=FAIL_CLOSED`: Clients attempting to start or resume workflows receive HTTP `503 Service Unavailable`.
- If `orynx.admission.failure-policy=FAIL_OPEN`: Workflows continue to execute, but global and per-user concurrency limits are bypassed, exposing worker pools to potential saturation.

## 3. Initial Investigation Steps
1. Check Redis container health:
   ```bash
   docker compose ps redis
   docker compose logs --tail=100 redis
   ```
2. Check `orchestrator-service` logs for Redis connection exceptions:
   ```bash
   docker compose logs --tail=100 orchestrator-service | grep -E "Redis|Lettuce|WorkflowAdmission"
   ```
3. Test Redis latency and connectivity from inside orchestrator container:
   ```bash
   docker compose exec orchestrator-service ping redis
   ```

## 4. Relevant Prometheus Queries
```promql
# Rate of admission errors
rate(orynx_workflow_admission_errors_total[5m])

# Redis connectivity status
redis_up

# Connected clients to Redis
redis_connected_clients
```

## 5. Remediation Steps
1. **Restart Redis**: If Redis is unresponsive or OOM killed, restart the container:
   ```bash
   docker compose restart redis
   ```
2. **Verify Network Connectivity**: Check Docker network health and DNS resolution of `redis:6379`.
3. **Verify Reconciliation**: Once Redis recovers, observe `AdmissionReconciliationJob` in `orchestrator-service` logs to confirm it reconciles the admission state with PostgreSQL.
