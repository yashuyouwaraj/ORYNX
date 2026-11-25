# Runbook: ObservabilityTelemetryPipelineFailure

Covers alerts: `OtelCollectorDown`, `TempoBackendDown`, `LokiBackendDown`, `OtelCollectorExportFailures`, telemetry ingestion failures, and Grafana data source failures.

## 1. What the Alert Means
One or more components of the centralized telemetry pipeline (`otel-collector`, `tempo`, or `loki`) are unreachable, failing health checks, or dropping exported spans/log records.

## 2. User / Business Impact
- **Core OrynX workflows are NOT impacted**: Backend services use bounded, non-blocking OTLP batch exporters with 5s timeouts and drop telemetry safely when the collector is down.
- **Operational Impact**: Distributed traces, centralized structured logs, and trace-to-log correlation in Grafana are temporarily degraded or unavailable until the telemetry pipeline recovers.

## 3. Initial Investigation Steps
1. Check container health and status for `otel-collector`, `tempo`, `loki`, and `grafana`.
2. Inspect `otel-collector` logs for OTLP export errors (`connection refused`, `deadline exceeded`, `memory_limiter` drops).
3. Verify `tempo` (`/ready`) and `loki` (`/ready`) readiness endpoints on loopback.
4. Test Grafana datasource connectivity under **Connections -> Data sources**.

## 4. Useful Commands
```bash
docker compose --profile observability ps
docker logs --tail 100 orynx-otel-collector-1
docker logs --tail 100 orynx-tempo-1
docker logs --tail 100 orynx-loki-1
curl -fsS http://127.0.0.1:3200/ready
curl -fsS http://127.0.0.1:3100/ready
```

## 5. Relevant Grafana Dashboards
- **OrynX Infrastructure**: `monitoring/grafana/dashboards/orynx-infrastructure.json`
- **OrynX Service Performance**: `monitoring/grafana/dashboards/orynx-service-performance.json`

## 6. Relevant Prometheus Queries
```promql
up{job=~"otel-collector|tempo|loki"}
rate(otelcol_exporter_send_failed_spans_total[5m])
rate(otelcol_exporter_send_failed_log_records_total[5m])
otelcol_process_memory_rss
```

## 7. Likely Root Causes
1. `tempo` or `loki` container restarted or OOM-killed under burst telemetry load.
2. Disk volume (`orynx_tempo_data` or `orynx_loki_data`) exhausted.
3. Invalid configuration edit in `otel-collector-config.yaml`, `tempo.yaml`, or `loki-config.yaml`.
4. Network isolation issue on `orynx-monitoring` bridge network.

## 8. Safe Recovery Actions
1. Validate configurations before restarting: `docker compose --profile observability config -q`.
2. Restart the affected observability container(s) without touching core database/Kafka/backend containers:
   ```bash
   docker compose --profile observability restart tempo loki otel-collector
   ```
3. If memory pressure caused OOM, temporarily reduce `OTEL_TRACES_SAMPLER_ARG` (e.g., to `0.25`) or raise `OTEL_COLLECTOR_MEM_LIMIT` / `LOKI_MEM_LIMIT` / `TEMPO_MEM_LIMIT` in `.env`.
4. If Grafana datasource shows error, verify `monitoring/grafana/provisioning/datasources/datasources.yml` and restart `grafana`.

## 9. Escalation Criteria
Escalate to Platform SRE if telemetry ingestion remains down for > 30 minutes, if local disk space on the host is > 90% full, or if collector restarts repeatedly with OOM errors despite reduced sampling.

## 10. Verification After Recovery
1. Confirm `up{job="otel-collector"} == 1`, `up{job="tempo"} == 1`, and `up{job="loki"} == 1` in Prometheus.
2. Confirm `rate(otelcol_exporter_send_failed_spans_total[5m]) == 0` and `rate(otelcol_exporter_send_failed_log_records_total[5m]) == 0`.
3. Execute a test API request through `gateway-service` and verify its trace appears in Tempo and correlated logs appear in Loki via Grafana Explore.
