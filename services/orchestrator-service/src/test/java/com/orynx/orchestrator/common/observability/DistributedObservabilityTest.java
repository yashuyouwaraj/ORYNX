package com.orynx.orchestrator.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.common.metrics.WorkflowMetrics;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.kafka.ExecutionRequestProducer;
import com.orynx.orchestrator.kafka.KafkaProducer;
import com.orynx.orchestrator.kafka.WorkflowCancellationProducer;
import com.orynx.orchestrator.kafka.WorkflowPauseProducer;
import com.orynx.orchestrator.kafka.WorkflowResumeProducer;
import com.orynx.orchestrator.resilience.metrics.ResilienceMetrics;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowEventPublisher;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowService;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.RecordInterceptor;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributedObservabilityTest {

    private final Yaml yaml = new Yaml();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void sanitizer_rejectsMalformedCrlfAndOversizedCorrelationIds() {
        assertThat(ObservabilitySanitizer.sanitizeCorrelationId("req-1234_abc.def:42/x")).isEqualTo("req-1234_abc.def:42/x");
        assertThat(ObservabilitySanitizer.sanitizeCorrelationId("valid\r\nforged-log-line")).isNull();
        assertThat(ObservabilitySanitizer.sanitizeCorrelationId("has space")).isNull();
        assertThat(ObservabilitySanitizer.sanitizeCorrelationId("   ")).isNull();
        assertThat(ObservabilitySanitizer.sanitizeCorrelationId(null)).isNull();
        assertThat(ObservabilitySanitizer.sanitizeCorrelationId("a".repeat(129))).isNull();
    }

    @Test
    void sanitizer_redactsSecretsTokensJwtsUrlsAndPrivateKeys() {
        String raw = "Auth failed: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3OCJ9.abcDEF123456 "
                + "password=SuperSecret123 api_key=sk-999999 jdbc:postgresql://admin:dbPass!@postgres:5432/orynx\n"
                + "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";

        String redacted = ObservabilitySanitizer.redactSensitiveText(raw);

        assertThat(redacted).doesNotContain("SuperSecret123");
        assertThat(redacted).doesNotContain("sk-999999");
        assertThat(redacted).doesNotContain("dbPass!");
        assertThat(redacted).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(redacted).doesNotContain("MIIEpAIBAAKCAQEA");
        assertThat(redacted).doesNotContain("\n");
        assertThat(redacted).contains("Bearer [REDACTED]");
        assertThat(redacted).contains("[REDACTED_PRIVATE_KEY]");
    }

    @Test
    void kafkaTracePropagator_preservesW3cTraceContinuityAndCorrelationAcrossProducerAndConsumer() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = tracerProvider.get("test-kafka-propagation");

            RecordHeaders headers = new RecordHeaders();
            MDC.put(KafkaTraceContextPropagator.MDC_CORRELATION_ID, "corr-wf-99");
            MDC.put(KafkaTraceContextPropagator.MDC_WORKFLOW_ID, "99");
            MDC.put(KafkaTraceContextPropagator.MDC_EXECUTION_ID, "501");

            Span producerSpan = tracer.spanBuilder("orynx.workflow.dispatch")
                    .setSpanKind(SpanKind.PRODUCER)
                    .startSpan();
            String expectedTraceId = producerSpan.getSpanContext().getTraceId();
            String expectedParentSpanId = producerSpan.getSpanContext().getSpanId();

            try (Scope ignored = producerSpan.makeCurrent()) {
                KafkaTraceContextPropagator.inject(headers, Context.current());
            } finally {
                producerSpan.end();
            }

            // Verify W3C traceparent and correlation headers were injected
            String traceparent = KafkaTraceContextPropagator.getHeader(headers, "traceparent");
            assertThat(traceparent).isNotNull().contains(expectedTraceId).contains(expectedParentSpanId);
            assertThat(KafkaTraceContextPropagator.getHeader(headers, "X-Correlation-ID")).isEqualTo("corr-wf-99");
            assertThat(KafkaTraceContextPropagator.getHeader(headers, "X-Workflow-ID")).isEqualTo("99");

            // Simulate crossing thread/service boundary: clear MDC before consumer extracts
            MDC.clear();

            Span consumerSpan = KafkaTraceContextPropagator.startConsumerSpan(
                    tracer, "orynx.kafka.consume.workflow-execution", headers, "workflow-execution"
            );
            try (Scope ignored = consumerSpan.makeCurrent()) {
                KafkaTraceContextPropagator.populateMdcFromHeaders(headers, Context.current());
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID)).isEqualTo("corr-wf-99");
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID)).isEqualTo("99");
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isEqualTo("501");
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_TRACE_ID)).isEqualTo(expectedTraceId);
            } finally {
                consumerSpan.end();
                KafkaTraceContextPropagator.clearKafkaMdc();
            }

            // Verify zero MDC leak after cleanup
            assertThat(MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID)).isNull();
            assertThat(MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID)).isNull();
            assertThat(MDC.get(KafkaTraceContextPropagator.MDC_TRACE_ID)).isNull();

            // Verify exported spans share the exact same traceId and parent-child link
            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);
            SpanData exportedConsumer = spans.stream()
                    .filter(s -> s.getName().equals("orynx.kafka.consume.workflow-execution"))
                    .findFirst()
                    .orElseThrow();
            assertThat(exportedConsumer.getTraceId()).isEqualTo(expectedTraceId);
            assertThat(exportedConsumer.getParentSpanId()).isEqualTo(expectedParentSpanId);
        }
    }

    @Test
    void kafkaInterceptors_injectOnSendAndCleanUpMdcAfterRecord() {
        ObservabilityKafkaConfig.TraceContextProducerInterceptor<String, String> producerInterceptor =
                new ObservabilityKafkaConfig.TraceContextProducerInterceptor<>();
        ObservabilityKafkaConfig.TraceContextRecordInterceptor<String, String> recordInterceptor =
                new ObservabilityKafkaConfig.TraceContextRecordInterceptor<>();

        MDC.put(KafkaTraceContextPropagator.MDC_CORRELATION_ID, "corr-interceptor-1");
        ProducerRecord<String, String> prodRecord = new ProducerRecord<>("workflow-execution", "42", "payload");
        producerInterceptor.onSend(prodRecord);
        assertThat(KafkaTraceContextPropagator.getHeader(prodRecord.headers(), "X-Correlation-ID"))
                .isEqualTo("corr-interceptor-1");

        MDC.clear();
        ConsumerRecord<String, String> consRecord = new ConsumerRecord<>("workflow-execution", 0, 1L, "42", "payload");
        prodRecord.headers().forEach(h -> consRecord.headers().add(h));

        recordInterceptor.intercept(consRecord, null);
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID)).isEqualTo("corr-interceptor-1");

        recordInterceptor.afterRecord(consRecord, null);
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void workflowTracingHelper_scopedLifecycleRestoresPriorMdcWithoutLeaking() {
        MDC.put(KafkaTraceContextPropagator.MDC_WORKFLOW_ID, "prior-wf");
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isNull();

        try (WorkflowTracingHelper.MdcScope ignored =
                     WorkflowTracingHelper.scopedWorkflowLifecycle(42L, 900L, 2L, "FAILED->RUNNING")) {
            assertThat(MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID)).isEqualTo("42");
            assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isEqualTo("900");
        }

        // Prior value restored, newly added key removed (zero MDC leak)
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID)).isEqualTo("prior-wf");
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void infrastructureConfigs_lokiCollectorDatasourcesDashboardsAndComposeAreValidAndHardened() throws Exception {
        Path repoRoot = resolveRepoRoot();

        // 1. Loki config: structured metadata enabled, 24h retention, low-cardinality labels, valid Loki 3.2.1 otlp_config
        Map<String, Object> loki = yaml.load(Files.readString(repoRoot.resolve("monitoring/loki/loki-config.yaml")));
        Map<String, Object> limits = (Map<String, Object>) loki.get("limits_config");
        assertThat(limits.get("allow_structured_metadata")).isEqualTo(true);
        assertThat(limits.get("retention_period")).isEqualTo("24h");
        Map<String, Object> otlpConfig = (Map<String, Object>) limits.get("otlp_config");
        assertThat(otlpConfig).isNotNull();
        Map<String, Object> resourceAttrs = (Map<String, Object>) otlpConfig.get("resource_attributes");
        assertThat(resourceAttrs.get("ignore_defaults")).isEqualTo(true);
        List<Map<String, Object>> logAttrs = (List<Map<String, Object>>) otlpConfig.get("log_attributes");
        assertThat(logAttrs).isNotEmpty();
        for (Map<String, Object> entry : logAttrs) {
            assertThat(entry.containsKey("attributes") || entry.containsKey("regex"))
                    .as("Loki 3.2.1 requires attributes or regex on otlp_config entries")
                    .isTrue();
        }
        assertThat(otlpConfig.containsKey("scope_attributes")).isFalse();

        // 2. OTel collector: traces + logs pipelines, memory_limiter, OTTL $$1 capture-group redaction, bounded queues
        String otelRaw = Files.readString(repoRoot.resolve("monitoring/otel/otel-collector-config.yaml"));
        assertThat(otelRaw).contains("memory_limiter", "filter/healthchecks", "attributes/redact_traces",
                "transform/redact_logs", "otlp/tempo", "otlphttp/loki", "sending_queue", "queue_size: 512",
                "\"$$1=[REDACTED]\"", "\"$$1[REDACTED]@\"", "[REDACTED_PRIVATE_KEY]");
        assertThat(otelRaw).doesNotContain("\"$1=[REDACTED]\"");

        // 3. Grafana datasources: Tempo -> Loki, Tempo -> Prometheus, Loki -> Tempo, Prometheus -> Tempo
        String dsRaw = Files.readString(repoRoot.resolve("monitoring/grafana/provisioning/datasources/datasources.yml"));
        assertThat(dsRaw).contains("uid: loki", "tracesToLogsV2", "tracesToMetrics",
                "derivedFields", "exemplarTraceIdDestinations");

        // 4. Grafana dashboard provisioning exists and points to /var/lib/grafana/dashboards
        Path dashProvisioningDir = repoRoot.resolve("monitoring/grafana/provisioning/dashboards");
        assertThat(Files.isDirectory(dashProvisioningDir)).isTrue();
        try (var stream = Files.list(dashProvisioningDir)) {
            List<Path> ymlFiles = stream.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).toList();
            assertThat(ymlFiles).isNotEmpty();
            String provContent = Files.readString(ymlFiles.get(0));
            assertThat(provContent).contains("/var/lib/grafana/dashboards");
        }

        // 5. Docker Compose: pinned Loki image, loopback binding, bounded OTLP queues
        String composeRaw = Files.readString(repoRoot.resolve("docker-compose.yml"));
        assertThat(composeRaw).contains("grafana/loki:3.2.1", "127.0.0.1:3100:3100",
                "OTEL_BSP_MAX_QUEUE_SIZE", "OTEL_BLRP_MAX_QUEUE_SIZE");
    }

    @Test
    void grafanaDashboards_allOrynXMetricsAndLabelsExistInRealPrometheusRegistry() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new WorkflowMetrics(registry);
        RedisCoordinationMetrics redisMetrics = new RedisCoordinationMetrics(registry);
        new ResilienceMetrics(registry);
        redisMetrics.recordOperation("admit", true, 12L);
        redisMetrics.recordAdmission("rejected", "global_limit_exceeded");

        Map<String, Set<String>> exportedMetrics = parsePrometheusMetrics(registry.scrape());

        // Execution-service metrics (verified directly in ExecutionObservabilityTest)
        Set<String> executionServiceMetrics = Set.of(
                "orynx_execution_attempts_total",
                "orynx_execution_retries_total",
                "orynx_execution_fenced_total",
                "orynx_worker_pool_rejected_total",
                "orynx_worker_pool_active",
                "orynx_worker_pool_queue_size",
                "orynx_execution_duration_seconds_bucket",
                "orynx_execution_duration_seconds_count",
                "orynx_execution_duration_seconds_sum",
                "orynx_execution_duration_seconds_max"
        );

        // Verify Redis coordination histogram and admission counter in real PrometheusMeterRegistry
        assertThat(exportedMetrics).containsKey("orynx_redis_operation_duration_seconds_bucket");
        assertThat(exportedMetrics.get("orynx_redis_operation_duration_seconds_bucket"))
                .contains("operation", "le");
        assertThat(exportedMetrics).containsKey("orynx_admission_requests_total");
        assertThat(exportedMetrics.get("orynx_admission_requests_total"))
                .contains("status", "reason")
                .doesNotContain("result");

        Path dashboardsDir = resolveRepoRoot().resolve("monitoring/grafana/dashboards");
        Pattern orynxMetricPattern = Pattern.compile("\\b(orynx_[a-z0-9_]+)\\b");

        // Validate every dashboard JSON in monitoring/grafana/dashboards
        try (var stream = Files.list(dashboardsDir)) {
            List<Path> dashboards = stream.filter(p -> p.toString().endsWith(".json")).toList();
            assertThat(dashboards).isNotEmpty();
            for (Path dashFile : dashboards) {
                JsonNode root = objectMapper.readTree(Files.readString(dashFile));
                assertThat(root.path("panels").isArray()).as(dashFile.getFileName().toString()).isTrue();
                for (JsonNode panel : root.path("panels")) {
                    String dsType = panel.path("datasource").path("type").asText("");
                    if ("loki".equals(dsType)) {
                        continue;
                    }
                    for (JsonNode target : panel.path("targets")) {
                        String expr = target.path("expr").asText("");
                        Matcher m = orynxMetricPattern.matcher(expr);
                        while (m.find()) {
                            String metricName = m.group(1);
                            boolean exists = exportedMetrics.containsKey(metricName)
                                    || executionServiceMetrics.contains(metricName);
                            assertThat(exists)
                                    .as("Dashboard %s panel '%s' references nonexistent metric '%s' in expr: %s",
                                            dashFile.getFileName(), panel.path("title").asText(), metricName, expr)
                                    .isTrue();
                        }
                    }
                }
            }
        }

        // Specifically verify M-01 Panel 5 (service performance) and Panel 6 (resilience recovery)
        JsonNode perfDash = objectMapper.readTree(Files.readString(dashboardsDir.resolve("orynx-service-performance.json")));
        JsonNode panel5 = findPanelById(perfDash, 5);
        String panel5Expr = panel5.path("targets").get(0).path("expr").asText();
        assertThat(panel5Expr).contains("orynx_redis_operation_duration_seconds_bucket")
                .contains("histogram_quantile(0.95")
                .contains("sum by (operation, le)")
                .doesNotContain("orynx_redis_coordination_duration_seconds_bucket");
        assertThat(panel5.path("fieldConfig").path("defaults").path("unit").asText()).isEqualTo("s");

        JsonNode resDash = objectMapper.readTree(Files.readString(dashboardsDir.resolve("orynx-resilience-recovery.json")));
        JsonNode panel6 = findPanelById(resDash, 6);
        String panel6Expr = panel6.path("targets").get(0).path("expr").asText();
        String panel6Legend = panel6.path("targets").get(0).path("legendFormat").asText();
        assertThat(panel6Expr).contains("orynx_admission_requests_total")
                .contains("status!=\"accepted\"")
                .contains("sum by (status, reason)")
                .doesNotContain("orynx_workflow_admission_total")
                .doesNotContain("result!=");
        assertThat(panel6Legend).contains("{{status}}").contains("{{reason}}").doesNotContain("{{result}}");
    }

    @Test
    void workflowService_scopesExecutionIdWorkflowIdAndEventIdAroundKafkaDispatchAndRestoresOnException() {
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        WorkflowTaskRepository taskRepo = mock(WorkflowTaskRepository.class);
        WorkflowExecutionRepository execRepo = mock(WorkflowExecutionRepository.class);
        ExecutionRequestProducer execProducer = mock(ExecutionRequestProducer.class);
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);

        WorkflowService service = new WorkflowService(
                wfRepo, mock(KafkaProducer.class), eventPublisher, taskRepo,
                mock(WorkflowExecutionEngine.class), execProducer, execRepo,
                mock(WorkflowCancellationProducer.class), mock(WorkflowPauseProducer.class),
                mock(WorkflowResumeProducer.class)
        );

        Workflow wf = Workflow.builder().id(55L).name("WF-55").status(WorkflowStatus.CREATED).userId(9L).build();
        WorkflowTask t1 = WorkflowTask.builder().id(1L).name("T1").executionOrder(1).maxRetries(1).status(TaskStatus.PENDING).workflow(wf).build();
        when(wfRepo.findByIdAndUserIdForUpdate(55L, 9L)).thenReturn(Optional.of(wf));
        when(wfRepo.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepo.findByWorkflowIdOrderByExecutionOrder(55L)).thenReturn(List.of(t1));
        when(execRepo.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution e = inv.getArgument(0);
            e.setId(888L);
            return e;
        });

        ObservabilityKafkaConfig.TraceContextProducerInterceptor<String, Object> producerInterceptor =
                new ObservabilityKafkaConfig.TraceContextProducerInterceptor<>();

        AtomicReference<ProducerRecord<String, Object>> capturedRecord = new AtomicReference<>();
        AtomicReference<WorkflowExecutionRequestEvent> capturedEvent = new AtomicReference<>();
        doAnswer(inv -> {
            WorkflowExecutionRequestEvent ev = inv.getArgument(0);
            capturedEvent.set(ev);
            ProducerRecord<String, Object> rec = new ProducerRecord<>("workflow-execution", String.valueOf(ev.getWorkflowId()), ev);
            producerInterceptor.onSend(rec);
            capturedRecord.set(rec);
            return null;
        }).when(execProducer).publishExecutionRequest(any());

        MDC.put(KafkaTraceContextPropagator.MDC_CORRELATION_ID, "corr-start-55");
        service.startWorkflow(55L, 9L);

        assertThat(capturedEvent.get().getExecutionId()).isEqualTo(888L);
        assertThat(KafkaTraceContextPropagator.getHeader(capturedRecord.get().headers(), "X-Workflow-ID")).isEqualTo("55");
        assertThat(KafkaTraceContextPropagator.getHeader(capturedRecord.get().headers(), "X-Execution-ID")).isEqualTo("888");
        assertThat(KafkaTraceContextPropagator.getHeader(capturedRecord.get().headers(), "X-Event-ID"))
                .isEqualTo(capturedEvent.get().getEventId());
        assertThat(KafkaTraceContextPropagator.getHeader(capturedRecord.get().headers(), "X-Correlation-ID"))
                .isEqualTo("corr-start-55");

        // MDC must be restored/cleared after dispatch
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID)).isNull();
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isNull();
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EVENT_ID)).isNull();

        // Exception path must NOT leak MDC
        wf.setStatus(WorkflowStatus.CREATED);
        doThrow(new RuntimeException("Kafka broker unavailable")).when(execProducer).publishExecutionRequest(any());
        assertThatThrownBy(() -> service.startWorkflow(55L, 9L)).isInstanceOf(RuntimeException.class);
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID)).isNull();
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isNull();
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EVENT_ID)).isNull();
    }

    @Test
    void concurrentWorkflowDispatches_doNotContaminateEachOthersMdc() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch proceed = new CountDownLatch(1);

            Future<String[]> f1 = pool.submit(() -> {
                try (WorkflowTracingHelper.MdcScope ignored =
                             WorkflowTracingHelper.scopedWorkflowLifecycle(101L, 1001L, 1L, "CREATED->RUNNING", "ev-101")) {
                    ready.countDown();
                    proceed.await(5, TimeUnit.SECONDS);
                    return new String[]{
                            MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID),
                            MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID),
                            MDC.get(KafkaTraceContextPropagator.MDC_EVENT_ID)
                    };
                }
            });
            Future<String[]> f2 = pool.submit(() -> {
                try (WorkflowTracingHelper.MdcScope ignored =
                             WorkflowTracingHelper.scopedWorkflowLifecycle(202L, 2002L, 1L, "CREATED->RUNNING", "ev-202")) {
                    ready.countDown();
                    proceed.await(5, TimeUnit.SECONDS);
                    return new String[]{
                            MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID),
                            MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID),
                            MDC.get(KafkaTraceContextPropagator.MDC_EVENT_ID)
                    };
                }
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            proceed.countDown();
            assertThat(f1.get(5, TimeUnit.SECONDS)).containsExactly("101", "1001", "ev-101");
            assertThat(f2.get(5, TimeUnit.SECONDS)).containsExactly("202", "2002", "ev-202");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recordInterceptor_avoidsDuplicateConsumerSpanWhenAgentSpanIsActiveAndEnrichesAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = tracerProvider.get("simulated-otel-javaagent");
            ObservabilityKafkaConfig.TraceContextRecordInterceptor<String, String> interceptor =
                    new ObservabilityKafkaConfig.TraceContextRecordInterceptor<>();

            ConsumerRecord<String, String> record = new ConsumerRecord<>("workflow-completed", 0, 5L, "42", "payload");
            record.headers().add("X-Correlation-ID", "corr-agent-1".getBytes());
            record.headers().add("X-Workflow-ID", "42".getBytes());
            record.headers().add("X-Execution-ID", "900".getBytes());
            record.headers().add("X-Event-ID", "ev-900".getBytes());

            // Simulate OTel Java agent having already created an active SpanKind.CONSUMER span
            Span agentConsumerSpan = tracer.spanBuilder("workflow-completed process")
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();
            try (Scope ignored = agentConsumerSpan.makeCurrent()) {
                interceptor.intercept(record, null);
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID)).isEqualTo("corr-agent-1");
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID)).isEqualTo("42");
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isEqualTo("900");
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EVENT_ID)).isEqualTo("ev-900");

                interceptor.failure(record, new IllegalStateException("conflict"), null);
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_ERROR_CATEGORY)).isEqualTo("STATE_CONFLICT");

                interceptor.afterRecord(record, null);
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID)).isNull();
                assertThat(MDC.get(KafkaTraceContextPropagator.MDC_ERROR_CATEGORY)).isNull();
                // Agent span must NOT have been ended by the interceptor
                assertThat(exporter.getFinishedSpanItems()).isEmpty();
            } finally {
                agentConsumerSpan.end();
            }

            // Exactly ONE consumer span exported, enriched with OrynX attributes
            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            SpanData spanData = spans.get(0);
            assertThat(spanData.getKind()).isEqualTo(SpanKind.CONSUMER);
            assertThat(spanData.getAttributes().get(AttributeKey.stringKey("orynx.correlation.id"))).isEqualTo("corr-agent-1");
            assertThat(spanData.getAttributes().get(AttributeKey.stringKey("orynx.workflow.id"))).isEqualTo("42");
            assertThat(spanData.getAttributes().get(AttributeKey.stringKey("orynx.execution.id"))).isEqualTo("900");
            assertThat(spanData.getAttributes().get(AttributeKey.stringKey("orynx.event.id"))).isEqualTo("ev-900");
            assertThat(spanData.getAttributes().get(AttributeKey.stringKey("orynx.error.category"))).isEqualTo("STATE_CONFLICT");
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void kafkaBeanPostProcessor_isStaticAndComposesExistingRecordInterceptor() throws Exception {
        Method factoryMethod = ObservabilityKafkaConfig.class.getDeclaredMethod("kafkaObservabilityBeanPostProcessor");
        assertThat(Modifier.isStatic(factoryMethod.getModifiers())).isTrue();

        BeanPostProcessor bpp = ObservabilityKafkaConfig.kafkaObservabilityBeanPostProcessor();
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();

        AtomicBoolean existingInterceptCalled = new AtomicBoolean(false);
        AtomicBoolean existingAfterCalled = new AtomicBoolean(false);
        AtomicReference<String> mdcSeenByExisting = new AtomicReference<>();

        RecordInterceptor<String, String> existing = new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                existingInterceptCalled.set(true);
                mdcSeenByExisting.set(MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID));
                return record;
            }

            @Override
            public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                existingAfterCalled.set(true);
            }
        };
        factory.setRecordInterceptor(existing);

        bpp.postProcessAfterInitialization(factory, "kafkaListenerContainerFactory");

        java.lang.reflect.Field f = org.springframework.util.ReflectionUtils.findField(factory.getClass(), "recordInterceptor");
        org.springframework.util.ReflectionUtils.makeAccessible(f);
        RecordInterceptor<String, String> composed = (RecordInterceptor<String, String>) f.get(factory);
        assertThat(composed).isInstanceOf(ObservabilityKafkaConfig.ComposingRecordInterceptor.class);

        ConsumerRecord<String, String> rec = new ConsumerRecord<>("workflow-execution", 0, 0L, "1", "v");
        rec.headers().add("X-Correlation-ID", "corr-composed-1".getBytes());
        composed.intercept(rec, null);
        assertThat(existingInterceptCalled.get()).isTrue();
        assertThat(mdcSeenByExisting.get()).isEqualTo("corr-composed-1");

        composed.afterRecord(rec, null);
        assertThat(existingAfterCalled.get()).isTrue();
        assertThat(MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void errorCategoryClassifier_returnsBoundedCategoriesWithoutLeakingMessages() {
        assertThat(ObservabilitySanitizer.classifyErrorCategory(new IllegalArgumentException("secret=abc"))).isEqualTo("VALIDATION");
        assertThat(ObservabilitySanitizer.classifyErrorCategory(new IllegalStateException("bad state"))).isEqualTo("STATE_CONFLICT");
        assertThat(ObservabilitySanitizer.classifyErrorCategory(new java.io.IOException("conn reset"))).isEqualTo("INFRASTRUCTURE");
        assertThat(ObservabilitySanitizer.classifyErrorCategory(new java.util.concurrent.TimeoutException("timed out"))).isEqualTo("TIMEOUT");
        assertThat(ObservabilitySanitizer.classifyErrorCategory(new SecurityException("denied"))).isEqualTo("SECURITY");
        assertThat(ObservabilitySanitizer.classifyErrorCategory(new RuntimeException("unclassified"))).isEqualTo("INTERNAL");
    }

    private static JsonNode findPanelById(JsonNode dashboard, int id) {
        for (JsonNode panel : dashboard.path("panels")) {
            if (panel.path("id").asInt() == id) {
                return panel;
            }
        }
        throw new AssertionError("Panel id " + id + " not found");
    }

    private static Map<String, Set<String>> parsePrometheusMetrics(String scrape) {
        Map<String, Set<String>> result = new HashMap<>();
        Pattern linePattern = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{([^}]*)})?\\s+");
        Pattern labelPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"");
        for (String line : scrape.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher m = linePattern.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                Set<String> labels = result.computeIfAbsent(name, k -> new HashSet<>());
                String labelBlock = m.group(3);
                if (labelBlock != null) {
                    Matcher lm = labelPattern.matcher(labelBlock);
                    while (lm.find()) {
                        labels.add(lm.group(1));
                    }
                }
            }
        }
        return result;
    }

    private Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("docker-compose.yml"))) {
            current = current.getParent();
        }
        assertThat(current).isNotNull();
        return current;
    }
}
