# Step 26 — Architecture Discovery: Distributed Observability & Operational Intelligence

**Baseline Branch:** `feature/workflow-monitoring` (`809c46b3366d5d76c032e6ccaf4e0d5bea1257de`)  
**Implementation Branch:** `feat/step26-distributed-observability`

## 1. Existing Observability Capabilities (Verified & Preserved)
Inspection of the repository confirmed a substantial foundation from Steps 22, 23, 24, and 25:
- **Runtime Auto-Instrumentation**: All 5 Spring Boot 3.5.x / Java 21 services (`gateway-service`, `auth-service`, `orchestrator-service`, `planning-service`, `execution-service`) package SHA-256 verified `opentelemetry-javaagent-2.11.0.jar` in their Dockerfiles with `OTEL_TRACES_SAMPLER=parentbased_traceidratio` and `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`.
- **Metrics & Alerting**: `micrometer-registry-prometheus` + interface-isolated `PrometheusMetricsServer` (`:9100/metrics` on `orynx-monitoring` `172.29.22.0/24`), `Prometheus` (`v2.55.1`), `Alertmanager` (`v0.27.0`), `postgres-exporter`, `redis-exporter`, `kafka-exporter`, SLO recording rules (`monitoring/prometheus/rules/slo-recording-rules.yml`), and 5 alert groups (`monitoring/prometheus/rules/alert-rules.yml`).
- **Trace Backend & Collector**: `otel/opentelemetry-collector-contrib:0.114.0` (`monitoring/otel/otel-collector-config.yaml`) and `grafana/tempo:2.6.1` (`monitoring/tempo/tempo.yaml`) under Docker Compose `profiles: ["observability"]`.
- **Logging**: `logstash-logback-encoder:8.0` in all 5 `logback-spring.xml` files emitting JSON in `docker` profile with `trace_id`, `span_id`, `requestId`, `workflowId`.
- **Grafana**: `grafana/grafana:11.3.1` with Prometheus, Tempo, and Alertmanager datasources and 6 dashboards (`orynx-platform-overview`, `orynx-backend-service-health`, `orynx-workflow-execution`, `orynx-kafka-messaging`, `orynx-infrastructure`, `orynx-slo-reliability`).
- **Security**: `ActuatorBoundaryFilter` / `ActuatorSecurityWebFilter` keeping application port actuator exposure locked to `health`, loopback-bound host UIs (`127.0.0.1`), and Step 25 resilience metrics (`ResilienceMetrics`).

## 2. Identified Gaps & Step 26 Engineering Scope
To achieve end-to-end distributed observability and operational intelligence without duplicating existing infrastructure, Step 26 addresses the following gaps:
1. **Centralized Log Storage & Trace-to-Log-to-Metric Correlation**:
   - No centralized log store exists (`OTEL_LOGS_EXPORTER: none`, no Loki service, no Loki datasource).
   - **Action**: Deploy **Grafana Loki (`grafana/loki:3.2.1`)** with 24h retention and `allow_structured_metadata: true` (preventing high-cardinality label explosion), add an OTLP logs pipeline in `otel-collector` with sensitive attribute redaction (`transform/redact`) exporting via `otlphttp/loki`, enable bounded non-blocking OTLP log export in services, and wire Grafana `tracesToLogsV2` (Tempo → Loki), `tracesToMetrics` (Tempo → Prometheus), `derivedFields` (Loki → Tempo), and `exemplarTraceIdDestinations` (Prometheus → Tempo).
2. **Semantic Workflow, Async Worker & Kafka Trace/Correlation Propagation**:
   - Services lack `io.opentelemetry:opentelemetry-api` (compatible with OTel Java Agent 2.11.0 via `GlobalOpenTelemetry` without conflicting SDKs).
   - `WorkflowExecutionDispatcher` submits async worker tasks to `ThreadPoolTaskExecutor` without propagating/restoring MDC (`workflowId`, `executionId`, `correlationId`, `workerId`, `attemptNumber`) or OTel `Context`, risking context loss and thread-pool MDC leakage.
   - Kafka producers/consumers lack explicit W3C `traceparent`/`tracestate` and `X-Correlation-ID`/`X-Workflow-ID`/`X-Execution-ID` header propagation and validation.
   - Incoming HTTP `X-Request-ID`/`X-Correlation-ID` headers are not sanitized against CRLF/log forging or excessive length.
   - **Action**: Add `io.opentelemetry:opentelemetry-api:1.44.1` across all 5 services; implement `CorrelationSanitizer`, `LogSanitizer`, `KafkaTracePropagator`, and `WorkflowTracingHelper`; propagate and clean up context across HTTP, Kafka, and `WorkflowExecutionDispatcher` worker pools; attach semantic workflow/execution/retry/recovery span attributes without sensitive payloads.
3. **Structured Logging Enrichment & Sanitization**:
   - Standardize `logback-spring.xml` across all 5 services to include `environment`, `correlationId`, `executionId`, `attemptNumber`, `workerId`, `eventId`, `errorCategory`, while preserving existing `trace_id`, `span_id`, `requestId`, `workflowId`.
   - Enforce log redaction for tokens, passwords, secrets, and authorization headers in both Java utilities and OTel Collector processors.
4. **Execution/Worker Metrics & Pipeline Health Alerts**:
   - Add bounded `ExecutionMetrics` in `execution-service` (execution duration, attempt counts, retries, worker active/queue/rejected, recovery/fencing counts) with zero high-cardinality labels.
   - Add Prometheus alerts for Loki down, Tempo down, and OTel Collector dropped spans/logs inside existing `orynx_platform_alerts` group.
5. **Operational Dashboards**:
   - Enhance existing dashboards and add **OrynX Service Performance** (`orynx-service-performance.json`) and **OrynX Resilience & Recovery** (`orynx-resilience-recovery.json`, integrating Step 25 `orynx_resilience_*` metrics).
6. **Frontend Observability**:
   - Add W3C `traceparent` + `X-Request-ID`/`X-Correlation-ID` generation in `frontend/src/services/api.ts`, safe error context enrichment, Grafana trace/log investigation link helpers, and STOMP correlation headers in `frontend/src/services/websocket.ts` without capturing tokens or sensitive payloads.
7. **Documentation, Runbooks, Testing & Adversarial Audit**:
   - Author `docs/DISTRIBUTED_OBSERVABILITY.md`, update `docs/OBSERVABILITY.md`, add operational runbooks in `docs/runbooks/`, write comprehensive unit/integration/infra tests, run full 5-service + frontend regression, and perform adversarial self-audit + remediation.
