# Runbook: RedisUnavailable (`RedisUnavailable`, `RedisExporterDown`)

## 1. What the Alert Means
- **`RedisUnavailable` (`critical`)**: `redis-exporter` reports `redis_up == 0` for more than 1 minute, indicating the Redis instance (`redis:6379`) is unreachable. (Note: `RedisUnavailable` automatically inhibits downstream `workflow`, `task`, and `slo` alerts in Alertmanager.)
- **`RedisExporterDown` (`warning`)**: Prometheus cannot scrape `redis-exporter:9121` for more than 1 minute.

## 2. User / Business Impact
Redis backs low-latency caching, distributed coordination helpers, and session/rate-limiting primitives. An outage degrades API responsiveness or causes Redis-dependent operations in backend services to fail.

## 3. Initial Investigation Steps
1. Check the status of `redis` and `redis-exporter` via `docker compose --profile observability ps`.
2. Inspect `redis` container logs for memory exhaustion (`OOM command not allowed`), AOF/RDB persistence write errors, or crash signals.
3. Test Redis connectivity directly with `redis-cli ping`.

## 4. Useful Commands
```bash
# Check Redis and Redis Exporter container health
docker compose --profile observability ps redis redis-exporter

# Inspect Redis logs
docker compose --profile observability logs --tail=100 redis

# Test Redis responsiveness and inspect memory info
docker compose --profile observability exec redis redis-cli ping
docker compose --profile observability exec redis redis-cli info memory
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Infrastructure & Backing Store Health* panel (`redis_up`).
- **OrynX Infrastructure** (`uid: orynx-infrastructure`) — *Redis Up*, *Redis Connected Clients*, and *Redis Memory Used*.

## 6. Relevant Prometheus Queries
```promql
# Redis up status
redis_up

# Exporter target health
up{job="redis-exporter"}

# Redis memory usage in bytes
redis_memory_used_bytes

# Connected clients
redis_connected_clients
```

## 7. Likely Root Causes
- **Container Crash / OOM**: `redis` container exceeded memory limits or encountered a host OOM kill.
- **Persistence Disk Full**: RDB snapshot (`BGSAVE`) failed because the host disk or `orynx_redis_data` volume is full.
- **Exporter Disconnected**: `redis-exporter` lost connectivity after a Redis container restart.

## 8. Safe Recovery Actions
1. Start or restart the `redis` and `redis-exporter` containers:
   ```bash
   docker compose --profile observability up -d redis redis-exporter
   ```
2. Verify `redis-cli ping` returns `PONG`.
3. If `redis-exporter` still reports `redis_up == 0` after Redis is healthy, restart `redis-exporter`:
   ```bash
   docker compose --profile observability restart redis-exporter
   ```

## 9. Escalation Criteria
- Escalate to the **Platform Infrastructure Lead** if Redis repeatedly crashes on startup or fails RDB/AOF loading on `orynx_redis_data`.

## 10. Verification After Recovery
1. Verify `redis_up == 1` and `up{job="redis-exporter"} == 1` in Prometheus.
2. Confirm `RedisUnavailable` and `RedisExporterDown` resolve in Alertmanager.
