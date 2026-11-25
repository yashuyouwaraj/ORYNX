# Runbook: ObservabilityTraceInvestigation

Covers missing workflow traces, broken Kafka trace propagation, and missing correlation context.

## 1. What the Alert Means
Operators investigating a workflow execution cannot find a complete end-to-end trace across Gateway → Orchestrator → Kafka → Execution Service, or logs lack `trace_id`/`correlationId`/`workflowId`.

## 2. User / Business Impact
- Workflow execution correctness is unaffected, but mean time to resolution (MTTR) increases during incident triage.

## 3. Initial Investigation Steps
1. Verify `OTEL_JAVAAGENT_ENABLED=true` and `OTEL_TRACES_SAMPLER_ARG` on the affected service.
2. Verify `otel-collector` and `tempo` are healthy.
3. Inspect structured logs in Loki for the `workflowId` or `correlationId` to see whether `trace_id` is present and consistent across `orchestrator-service` and `execution-service`.
4. Check if `traceparent` header was dropped by an upstream proxy or malformed `X-Correlation-ID` was sanitized.

## 4. Useful Commands
```bash
# Query Tempo API directly on loopback for a trace ID
curl -fsS http://127.0.0.1:3200/api/traces/<TRACE_ID>
# Check Loki logs for a specific workflowId
curl -G -fsS "http://127.0.0.1:3100/loki/api/v1/query_range" --data-urlencode 'query={service_name=~".*-service"} | workflowId="42"'
```

## 5. Relevant Grafana Dashboards
- **OrynX Workflow Execution**: `monitoring/grafana/dashboards/orynx-workflow-execution.json`
- **OrynX Service Performance**: `monitoring/grafana/dashboards/orynx-service-performance.json`
- **OrynX Resilience, Workers & Recovery**: `monitoring/grafana/dashboards/orynx-resilience-recovery.json`

## 6. Relevant Prometheus Queries
```promql
rate(otelcol_exporter_sent_spans_total[5m])
up{job=~"otel-collector|tempo|loki"}
```

## 7. Likely Root Causes
1. Request was not sampled (`OTEL_TRACES_SAMPLER_ARG < 1.0`).
2. `ObservabilityKafkaConfig` / `ExecutionObservabilityKafkaConfig` interceptors disabled or bypassed.
3. Async thread pool submitted work without capturing OTel `Context` or MDC map.
4. Malformed `X-Request-ID` / `X-Correlation-ID` header rejected by `ObservabilitySanitizer` (e.g., contained spaces, CRLF, or exceeded 128 chars).

## 8. Safe Recovery Actions
1. Confirm `ObservabilityKafkaConfig` and `ExecutionObservabilityKafkaConfig` beans are active.
2. Ensure clients send valid W3C `traceparent` and safe `X-Correlation-ID` headers (`^[A-Za-z0-9._:/-]{1,128}$`).
3. Temporarily set `OTEL_TRACES_SAMPLER_ARG=1.0` in non-production environments when reproducing an issue.

## 9. Escalation Criteria
Escalate if traces are missing at 100% sampling while `otel-collector` and `tempo` report healthy and zero export failures.

## 10. Verification After Recovery
1. Submit a workflow via Gateway with an explicit `X-Correlation-ID`.
2. Confirm a single `trace_id` spans `gateway-service`, `orchestrator-service`, Kafka `workflow-execution`, `execution-service` (`orynx.worker.execute`, `orynx.task.execute`), and back to `orchestrator-service`.
3. Confirm clicking `TraceID` in Loki opens the exact trace in Tempo and vice versa.
