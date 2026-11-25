# Runbook: ObservabilityCardinalityAndVolume

Covers high telemetry volume, collector memory pressure, and Prometheus/Loki high-cardinality regressions.

## 1. What the Alert Means
Telemetry ingestion volume or unique metric/log stream series count is growing faster than configured resource budgets, risking collector drops, Prometheus TSDB bloat, or Loki stream limit rejections.

## 2. User / Business Impact
- Core workflow execution remains protected by bounded client/collector queues.
- Monitoring latency increases, Grafana queries slow down, and collector `memory_limiter` may drop spans/logs.

## 3. Initial Investigation Steps
1. Check Prometheus TSDB head series count (`prometheus_tsdb_head_series`) and top metric names by cardinality.
2. Check Loki active stream count (`loki_ingester_memory_streams`) and ingestion rate.
3. Check OTel Collector span/log throughput and refused counts.
4. Audit recent code changes for forbidden metric labels (`workflowId`, `executionId`, `traceId`, `userId`, `requestId`, `workerId`).

## 4. Useful Commands
```bash
curl -fsS http://127.0.0.1:9090/api/v1/status/tsdb | head -c 2000
docker stats --no-stream
```

## 5. Relevant Grafana Dashboards
- **OrynX Infrastructure**: `monitoring/grafana/dashboards/orynx-infrastructure.json`
- **OrynX Backend Service Health**: `monitoring/grafana/dashboards/orynx-backend-service-health.json`

## 6. Relevant Prometheus Queries
```promql
topk(10, count by (__name__)({__name__=~"orynx_.*"}))
rate(otelcol_receiver_accepted_spans_total[5m])
rate(otelcol_processor_refused_spans_total[5m])
```

## 7. Likely Root Causes
1. High-cardinality identifier accidentally added as a Micrometer tag or Loki indexed label.
2. `OTEL_TRACES_SAMPLER_ARG=1.0` left enabled under high load.
3. Noisy debug logging or healthcheck spans bypassing `filter/healthchecks`.

## 8. Safe Recovery Actions
1. Lower `OTEL_TRACES_SAMPLER_ARG` (e.g. `0.1`) in `.env` and recreate services.
2. Verify `monitoring/loki/loki-config.yaml` only indexes `service.name` and `deployment.environment` as stream labels (keeping `trace_id`/`workflowId` in structured metadata).
3. Remove any unbounded Micrometer tag immediately and verify `ObservabilityMetricsAndConfigTest` passes.

## 9. Escalation Criteria
Escalate if Prometheus head series exceeds 100,000 or Loki drops > 5% of log lines after sampling adjustments.

## 10. Verification After Recovery
1. Confirm `otelcol_processor_refused_spans_total` stops increasing.
2. Confirm `topk(10, count by (__name__)({__name__=~"orynx_.*"}))` shows strictly bounded series counts.
3. Verify Grafana dashboards load within 3 seconds.
