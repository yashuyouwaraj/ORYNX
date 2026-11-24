# Runbook: PostgreSQLUnavailable (`PostgreSQLUnavailable`, `PostgreSQLConnectionsHigh`, `PostgresExporterDown`)

## 1. What the Alert Means
- **`PostgreSQLUnavailable` (`critical`)**: `postgres-exporter` reports `pg_up == 0` for more than 1 minute, indicating the PostgreSQL database (`postgres:5432`) is unreachable or rejecting connections. (Note: `PostgreSQLUnavailable` inhibits `warning`-level `workflow` and `task` symptoms in Alertmanager while keeping `critical` workflow/task/SLO alerts visible.)
- **`PostgreSQLConnectionsHigh` (`warning`)**: Active database connections exceed 80% of `max_connections` (`orynx:postgres:connection_utilization_ratio > 0.80`) for more than 3 minutes.
- **`PostgresExporterDown` (`critical`)**: Prometheus cannot scrape `postgres-exporter:9187` for more than 1 minute, blinding database health and connection pool monitoring.

## 2. User / Business Impact
PostgreSQL stores users, credentials, workflows, DAG task states, execution history, timeline events, and distributed execution coordination leases. When PostgreSQL is unavailable or out of connections, authentication, workflow creation, orchestration, and task execution fail.

## 3. Initial Investigation Steps
1. Check `postgres` and `postgres-exporter` container states via `docker compose --profile observability ps`.
2. Inspect `postgres` container logs for fatal errors, out-of-disk errors, or authentication rejections.
3. For `PostgreSQLConnectionsHigh`, query `pg_stat_activity` to identify which service or query state (`active`, `idle in transaction`, `idle`) holds the connections.

## 4. Useful Commands
```bash
# Check postgres and exporter health
docker compose --profile observability ps postgres postgres-exporter

# Inspect PostgreSQL container logs
docker compose --profile observability logs --tail=150 postgres

# Query active connections by application/state inside Postgres
docker compose --profile observability exec postgres psql -U postgres -d orynx -c "SELECT state, usename, count(*) FROM pg_stat_activity GROUP BY state, usename;"

# Check long-running or blocked queries
docker compose --profile observability exec postgres psql -U postgres -d orynx -c "SELECT pid, now() - query_start AS duration, state, left(query, 80) FROM pg_stat_activity WHERE state != 'idle' ORDER BY duration DESC;"
```

## 5. Relevant Grafana Dashboards
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *PostgreSQL Connection Utilization* and *Infrastructure & Backing Store Health*.
- **OrynX Infrastructure** (`uid: orynx-infrastructure`) — *PostgreSQL Up*, *PostgreSQL Active Connections*, and *Committed Transactions*.

## 6. Relevant Prometheus Queries
```promql
# PostgreSQL up status
pg_up

# Connection utilization ratio (active / max_connections)
orynx:postgres:connection_utilization_ratio

# Active connections vs max connections
sum(pg_stat_activity_count)
max(pg_settings_max_connections)
```

## 7. Likely Root Causes
- **Container Stopped or Restarting**: `postgres` crashed or ran out of storage on volume `orynx_postgres_data`.
- **Connection Leak / Pool Exhaustion**: Stalled transactions (`idle in transaction`) or misconfigured HikariCP pool sizes consuming `max_connections`.
- **Monitoring User Credential Mismatch**: `POSTGRES_MONITOR_PASSWORD` changed without re-running `ensure-monitoring-user.sh`, causing `pg_up == 0` in `postgres-exporter` while the DB itself is up.

## 8. Safe Recovery Actions
1. If `postgres` is stopped or unhealthy, start/restart it and verify `ensure-monitoring-user.sh` succeeds:
   ```bash
   docker compose --profile observability up -d postgres postgres-exporter
   ```
2. **DISRUPTIVE ACTION WARNING**: If `PostgreSQLConnectionsHigh` is caused by orphaned `idle in transaction` connections holding locks for several minutes, inspect the PIDs and owning service first. Calling `pg_terminate_backend(pid)` **aborts the in-flight transaction on that connection, rolls back uncommitted work, and causes the owning service's HikariCP pool to log connection-closed errors and reconnect**. Only terminate confirmed stalled sessions:
   ```bash
   # Use POSTGRES_USER (default: postgres) and POSTGRES_DB (default: orynx)
   docker compose --profile observability exec postgres psql -U postgres -d orynx -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'idle in transaction' AND now() - state_change > interval '5 minutes';"
   ```
3. If `pg_up == 0` is due only to `postgres-exporter` connection state after a DB restart, restart `postgres-exporter`:
   ```bash
   docker compose --profile observability restart postgres-exporter
   ```

## 9. Escalation Criteria
- Escalate immediately to the **Database Administrator / Platform Lead** if PostgreSQL logs show WAL corruption, disk exhaustion, or failure to mount `orynx_postgres_data`.

## 10. Verification After Recovery
1. Verify `pg_up == 1` and `orynx:postgres:connection_utilization_ratio < 0.80` in Prometheus.
2. Verify `/actuator/health/readiness` returns `UP` for `auth-service`, `orchestrator-service`, and `execution-service`.
3. Confirm `PostgreSQLUnavailable` / `PostgreSQLConnectionsHigh` resolves in Alertmanager.
