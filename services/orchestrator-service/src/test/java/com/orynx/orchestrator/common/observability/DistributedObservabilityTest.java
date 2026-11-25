package com.orynx.orchestrator.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

        // 1. Loki config: structured metadata enabled, 24h retention, low-cardinality labels
        Map<String, Object> loki = yaml.load(Files.readString(repoRoot.resolve("monitoring/loki/loki-config.yaml")));
        Map<String, Object> limits = (Map<String, Object>) loki.get("limits_config");
        assertThat(limits.get("allow_structured_metadata")).isEqualTo(true);
        assertThat(limits.get("retention_period")).isEqualTo("24h");

        // 2. OTel collector: traces + logs pipelines, memory_limiter, redaction, bounded queues
        String otelRaw = Files.readString(repoRoot.resolve("monitoring/otel/otel-collector-config.yaml"));
        assertThat(otelRaw).contains("memory_limiter", "filter/healthchecks", "attributes/redact_traces",
                "transform/redact_logs", "otlp/tempo", "otlphttp/loki", "sending_queue", "queue_size: 512");

        // 3. Grafana datasources: Tempo -> Loki, Tempo -> Prometheus, Loki -> Tempo, Prometheus -> Tempo
        String dsRaw = Files.readString(repoRoot.resolve("monitoring/grafana/provisioning/datasources/datasources.yml"));
        assertThat(dsRaw).contains("uid: loki", "tracesToLogsV2", "tracesToMetrics",
                "derivedFields", "exemplarTraceIdDestinations");

        // 4. New dashboards parse as valid JSON with real metric/log queries
        for (String dash : List.of("orynx-service-performance.json", "orynx-resilience-recovery.json")) {
            JsonNode node = objectMapper.readTree(
                    Files.readString(repoRoot.resolve("monitoring/grafana/dashboards/" + dash))
            );
            assertThat(node.path("panels").isArray()).isTrue();
            assertThat(node.path("panels").size()).isGreaterThanOrEqualTo(6);
        }

        // 5. Docker Compose: pinned Loki image, loopback binding, bounded OTLP queues
        String composeRaw = Files.readString(repoRoot.resolve("docker-compose.yml"));
        assertThat(composeRaw).contains("grafana/loki:3.2.1", "127.0.0.1:3100:3100",
                "OTEL_BSP_MAX_QUEUE_SIZE", "OTEL_BLRP_MAX_QUEUE_SIZE");
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
