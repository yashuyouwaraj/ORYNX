package com.orynx.execution.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.coordination.StaleWorkerExecutionException;
import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowExecutionDispatcher;
import com.orynx.execution.execution.WorkflowExecutionEngine;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.worker.WorkerQueueFullException;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionObservabilityTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void executionSanitizer_rejectsCrlfAndRedactsSecrets() {
        assertThat(ExecutionObservabilitySanitizer.sanitizeCorrelationId("corr-123")).isEqualTo("corr-123");
        assertThat(ExecutionObservabilitySanitizer.sanitizeCorrelationId("bad\ninjection")).isNull();
        String redacted = ExecutionObservabilitySanitizer.redactSensitiveText("Bearer secretToken123 password=myPass");
        assertThat(redacted).doesNotContain("secretToken123").doesNotContain("myPass");
    }

    @Test
    void executionKafkaTracePropagator_preservesTraceIdAcrossBoundaries() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = provider.get("test-exec-kafka");
            RecordHeaders headers = new RecordHeaders();
            Span producerSpan = tracer.spanBuilder("producer").setSpanKind(SpanKind.PRODUCER).startSpan();
            String traceId = producerSpan.getSpanContext().getTraceId();
            try (Scope ignored = producerSpan.makeCurrent()) {
                ExecutionKafkaTracePropagator.inject(headers, Context.current());
            } finally {
                producerSpan.end();
            }

            Span consumerSpan = ExecutionKafkaTracePropagator.startConsumerSpan(
                    tracer, "consumer", headers, "workflow-execution"
            );
            consumerSpan.end();

            assertThat(consumerSpan.getSpanContext().getTraceId()).isEqualTo(traceId);
        }
    }

    @Test
    void executionMetrics_exposesBoundedMetricsWithoutHighCardinalityLabels() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ExecutionMetrics metrics = new ExecutionMetrics(registry);

        metrics.recordTaskResult(true, 3);
        metrics.recordTaskResult(false, 2);
        metrics.recordWorkerFenced();
        metrics.recordWorkerRejected();
        metrics.recordExecutionDuration(true, 1250L);
        metrics.updatePoolStats(2, 5);

        String scrape = registry.scrape();
        assertThat(scrape).contains("orynx_execution_attempts_total{outcome=\"SUCCESS\"} 1.0");
        assertThat(scrape).contains("orynx_execution_attempts_total{outcome=\"FAILED\"} 1.0");
        assertThat(scrape).contains("orynx_execution_retries_total 3.0");
        assertThat(scrape).contains("orynx_execution_fenced_total 1.0");
        assertThat(scrape).contains("orynx_worker_pool_rejected_total 1.0");
        assertThat(scrape).contains("orynx_worker_pool_active 2.0");
        assertThat(scrape).contains("orynx_worker_pool_queue_size 5.0");
        assertThat(scrape).doesNotContain("workflowId", "executionId", "traceId", "workerId");
    }

    @Test
    void workflowExecutionDispatcher_propagatesMdcToWorkerAndClearsOnThreadReuse() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();

        try {
            WorkflowExecutionEngine engine = mock(WorkflowExecutionEngine.class);
            ExecutionCoordinationService coordinationService = mock(ExecutionCoordinationService.class);
            ActiveExecutionTracker tracker = new ActiveExecutionTracker();
            ExecutionWorkerProperties props = new ExecutionWorkerProperties();
            PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            ExecutionMetrics metrics = new ExecutionMetrics(registry);

            WorkflowExecutionDispatcher dispatcher = new WorkflowExecutionDispatcher(
                    executor, engine, coordinationService, tracker, props
            );
            ReflectionTestUtils.setField(dispatcher, "executionMetrics", metrics);

            AtomicReference<String> observedCorrInFirstRun = new AtomicReference<>();
            AtomicReference<String> observedExecInFirstRun = new AtomicReference<>();
            AtomicReference<String> observedEventInFirstRun = new AtomicReference<>();
            AtomicReference<String> observedCorrInSecondRun = new AtomicReference<>();
            AtomicReference<String> observedExecInSecondRun = new AtomicReference<>();
            AtomicReference<String> observedEventInSecondRun = new AtomicReference<>();
            CountDownLatch firstDone = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);

            doAnswer(inv -> {
                WorkflowExecutionRequestEvent ev = inv.getArgument(0);
                if (ev.getWorkflowId() == 101L) {
                    observedCorrInFirstRun.set(MDC.get("correlationId"));
                    observedExecInFirstRun.set(MDC.get("executionId"));
                    observedEventInFirstRun.set(MDC.get("eventId"));
                    firstDone.countDown();
                } else {
                    observedCorrInSecondRun.set(MDC.get("correlationId"));
                    observedExecInSecondRun.set(MDC.get("executionId"));
                    observedEventInSecondRun.set(MDC.get("eventId"));
                    secondDone.countDown();
                }
                return null;
            }).when(engine).execute(any(), anyLong(), anyString());

            // First dispatch WITH canonical event.executionId and eventId
            MDC.put("correlationId", "corr-worker-101");
            dispatcher.dispatch(WorkflowExecutionRequestEvent.builder()
                    .workflowId(101L)
                    .workflowName("WF-101")
                    .executionId(777L)
                    .eventId("ev-canon-101")
                    .build(), 2L);
            assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observedCorrInFirstRun.get()).isEqualTo("corr-worker-101");
            assertThat(observedExecInFirstRun.get()).isEqualTo("777");
            assertThat(observedEventInFirstRun.get()).isEqualTo("ev-canon-101");

            // Second dispatch on the SAME single worker thread WITHOUT executionId/correlationId (legacy fallback)
            MDC.clear();
            dispatcher.dispatch(WorkflowExecutionRequestEvent.builder()
                    .workflowId(202L)
                    .workflowName("WF-202")
                    .build(), 1L);
            assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
            // Must NOT leak corr-worker-101 or ev-canon-101 onto the reused worker thread
            assertThat(observedCorrInSecondRun.get()).isNull();
            assertThat(observedEventInSecondRun.get()).isNull();
            assertThat(observedExecInSecondRun.get()).isEqualTo("wf-202-gen-1");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void executionRecordInterceptor_avoidsDuplicateConsumerSpanWhenAgentSpanIsActive() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = provider.get("simulated-otel-javaagent");
            ExecutionObservabilityKafkaConfig.TraceContextRecordInterceptor<String, String> interceptor =
                    new ExecutionObservabilityKafkaConfig.TraceContextRecordInterceptor<>();

            ConsumerRecord<String, String> record = new ConsumerRecord<>("workflow-execution", 0, 2L, "101", "payload");
            record.headers().add("X-Correlation-ID", "corr-exec-1".getBytes());
            record.headers().add("X-Workflow-ID", "101".getBytes());
            record.headers().add("X-Execution-ID", "777".getBytes());
            record.headers().add("X-Event-ID", "ev-777".getBytes());

            Span agentSpan = tracer.spanBuilder("workflow-execution process")
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();
            try (Scope ignored = agentSpan.makeCurrent()) {
                interceptor.intercept(record, null);
                assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EXECUTION_ID)).isEqualTo("777");
                assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID)).isEqualTo("ev-777");
                interceptor.afterRecord(record, null);
                assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EXECUTION_ID)).isNull();
                assertThat(exporter.getFinishedSpanItems()).isEmpty();
            } finally {
                agentSpan.end();
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("orynx.execution.id"))).isEqualTo("777");
            assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("orynx.event.id"))).isEqualTo("ev-777");
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executionKafkaBeanPostProcessor_isStaticAndComposesExistingRecordInterceptor() throws Exception {
        Method m = ExecutionObservabilityKafkaConfig.class.getDeclaredMethod("executionKafkaObservabilityBeanPostProcessor");
        assertThat(Modifier.isStatic(m.getModifiers())).isTrue();

        BeanPostProcessor bpp = ExecutionObservabilityKafkaConfig.executionKafkaObservabilityBeanPostProcessor();
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        AtomicBoolean delegateCalled = new AtomicBoolean(false);
        factory.setRecordInterceptor(new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                delegateCalled.set(true);
                return record;
            }
        });

        bpp.postProcessAfterInitialization(factory, "kafkaListenerContainerFactory");
        RecordInterceptor<String, String> composed =
                (RecordInterceptor<String, String>) ReflectionTestUtils.getField(factory, "recordInterceptor");
        assertThat(composed).isInstanceOf(ExecutionObservabilityKafkaConfig.ComposingRecordInterceptor.class);

        ConsumerRecord<String, String> rec = new ConsumerRecord<>("workflow-execution", 0, 0L, "1", "v");
        composed.intercept(rec, null);
        composed.afterRecord(rec, null);
        assertThat(delegateCalled.get()).isTrue();
    }

    @Test
    void executionErrorCategoryClassifier_returnsBoundedCategories() {
        assertThat(ExecutionObservabilitySanitizer.classifyErrorCategory(
                new StaleWorkerExecutionException("fenced"))).isEqualTo("FENCING");
        assertThat(ExecutionObservabilitySanitizer.classifyErrorCategory(
                new WorkerQueueFullException("full", null))).isEqualTo("CAPACITY");
        assertThat(ExecutionObservabilitySanitizer.classifyErrorCategory(
                new IllegalArgumentException("bad arg"))).isEqualTo("VALIDATION");
        assertThat(ExecutionObservabilitySanitizer.classifyErrorCategory(
                new RuntimeException("other"))).isEqualTo("INTERNAL");
    }

    @Test
    void executionSanitizer_redactsJsonQuotedSingleQuotedAndNestedSecretsWhilePreservingSafeFields() {
        String raw = "{\"workflowId\":\"101\",\"auth\":{\"password\":\"execJsonPass\",\"api_key\":\"sk-exec-999\","
                + "\"token\":\"tok-exec-777\",\"authorization\":\"Bearer execBearer555\"},'password':'execSinglePass'} "
                + "password=eqExecPass password: colonExecPass api_key = spaceExecKey\nFORGED";
        String redacted = ExecutionObservabilitySanitizer.redactSensitiveText(raw);

        assertThat(redacted).doesNotContain("execJsonPass", "sk-exec-999", "tok-exec-777",
                "execBearer555", "execSinglePass", "eqExecPass", "colonExecPass", "spaceExecKey", "\n");
        assertThat(redacted).contains("\"workflowId\":\"101\"");
        assertThat(redacted).contains("\"password\":\"[REDACTED]\"");

        String compound = "access_token=eAcc1 JWT_SECRET=eJwt2 SPRING_DATASOURCE_PASSWORD=eDb3 "
                + "Authorization: Basic dXNlcjpwYXNz "
                + "{\"password\":\"it's-apos777\",\"secret\":\"esc\\\"aped888\"} "
                + "tokenType=Bearer passwordPolicy=strong";
        String redactedCompound = ExecutionObservabilitySanitizer.redactSensitiveText(compound);
        assertThat(redactedCompound).doesNotContain("eAcc1", "eJwt2", "eDb3", "dXNlcjpwYXNz", "apos777", "aped888");
        assertThat(redactedCompound).contains("tokenType=Bearer passwordPolicy=strong");
    }

    @Test
    void grafanaDashboards_allExecutionMetricsAndLabelsExistInRealExecutionPrometheusRegistry() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ExecutionMetrics metrics = new ExecutionMetrics(registry);
        metrics.recordTaskResult(true, 2);
        metrics.recordTaskResult(false, 1);
        metrics.recordWorkerFenced();
        metrics.recordWorkerRejected();
        metrics.recordExecutionDuration(true, 250L);
        metrics.recordExecutionDuration(false, 500L);
        metrics.updatePoolStats(2, 4);

        Map<String, Set<String>> exported = new HashMap<>();
        Pattern linePattern = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{([^}]*)})?\\s+");
        Pattern labelPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"");
        for (String line : registry.scrape().split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher m = linePattern.matcher(line);
            if (m.find()) {
                Set<String> labels = exported.computeIfAbsent(m.group(1), k -> new HashSet<>());
                if (m.group(3) != null) {
                    Matcher lm = labelPattern.matcher(m.group(3));
                    while (lm.find()) {
                        labels.add(lm.group(1));
                    }
                }
            }
        }

        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("docker-compose.yml"))) {
            current = current.getParent();
        }
        assertThat(current).isNotNull();

        JsonNode resDash = new ObjectMapper().readTree(
                Files.readString(current.resolve("monitoring/grafana/dashboards/orynx-resilience-recovery.json"))
        );
        Pattern execMetricPattern = Pattern.compile("\\b(orynx_(?:execution|worker)_[a-z0-9_]+)\\b");
        int verifiedMetricRefs = 0;
        for (JsonNode panel : resDash.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expr = target.path("expr").asText("");
                Matcher m = execMetricPattern.matcher(expr);
                while (m.find()) {
                    String metricName = m.group(1);
                    assertThat(exported)
                            .as("Execution metric '%s' referenced in dashboard panel '%s' must exist in real ExecutionMetrics scrape",
                                    metricName, panel.path("title").asText())
                            .containsKey(metricName);
                    verifiedMetricRefs++;
                }
            }
        }
        assertThat(verifiedMetricRefs).isGreaterThanOrEqualTo(6);
        assertThat(exported.get("orynx_execution_duration_seconds_bucket")).contains("outcome", "le");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionEventProducer_propagatesOutgoingCanonicalEventIdAndRestoresPriorMdcOnSuccessFailureAndConcurrency() throws Exception {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        ExecutionObservabilityKafkaConfig.TraceContextProducerInterceptor<String, Object> interceptor =
                new ExecutionObservabilityKafkaConfig.TraceContextProducerInterceptor<>();

        List<String> headerEventIds = new ArrayList<>();
        List<String> mdcEventIdsDuringSend = new ArrayList<>();
        List<String> headerExecutionIds = new ArrayList<>();
        List<String> headerCorrelationIds = new ArrayList<>();

        when(template.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            String topic = inv.getArgument(0);
            String key = inv.getArgument(1);
            Object payload = inv.getArgument(2);
            ProducerRecord<String, Object> rec = new ProducerRecord<>(topic, key, payload);
            interceptor.onSend(rec);
            synchronized (headerEventIds) {
                headerEventIds.add(ExecutionKafkaTracePropagator.getHeader(rec.headers(), ExecutionKafkaTracePropagator.HEADER_EVENT_ID));
                mdcEventIdsDuringSend.add(MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID));
                headerExecutionIds.add(ExecutionKafkaTracePropagator.getHeader(rec.headers(), ExecutionKafkaTracePropagator.HEADER_EXECUTION_ID));
                headerCorrelationIds.add(ExecutionKafkaTracePropagator.getHeader(rec.headers(), ExecutionKafkaTracePropagator.HEADER_CORRELATION_ID));
            }
            return CompletableFuture.completedFuture(null);
        });

        ExecutionEventProducer producer = new ExecutionEventProducer(template);

        // Simulate worker thread carrying incoming execution-request eventId, canonical executionId, correlationId
        MDC.put(ExecutionKafkaTracePropagator.MDC_EVENT_ID, "incoming-request-uuid-999");
        MDC.put(ExecutionKafkaTracePropagator.MDC_EXECUTION_ID, "777");
        MDC.put(ExecutionKafkaTracePropagator.MDC_WORKFLOW_ID, "42");
        MDC.put(ExecutionKafkaTracePropagator.MDC_CORRELATION_ID, "corr-exec-777");

        producer.publishTaskStarted(TaskStartedEvent.builder()
                .workflowId(42L).workflowName("WF").taskName("T1").executionOrder(1).generation(1L)
                .eventId("wf:42:gen:1:task-started:1").build());
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID)).isEqualTo("incoming-request-uuid-999");

        producer.publishTaskCompleted(TaskCompletedEvent.builder()
                .workflowId(42L).workflowName("WF").taskName("T1").executionOrder(1).success(true).attempts(1).generation(1L)
                .eventId("wf:42:gen:1:task-completed:1").build());
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID)).isEqualTo("incoming-request-uuid-999");

        producer.publishWorkflowCompleted(WorkflowCompletedEvent.builder()
                .workflowId(42L).workflowName("WF").success(true).generation(1L)
                .eventId("wf:42:gen:1:workflow-completed").build());
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID)).isEqualTo("incoming-request-uuid-999");

        producer.publishWorkflowExecutionPaused(WorkflowExecutionPausedEvent.builder()
                .workflowId(42L).workflowName("WF").lastCompletedExecutionOrder(1).generation(1L)
                .eventId("wf:42:gen:1:workflow-paused:1").build());
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID)).isEqualTo("incoming-request-uuid-999");

        assertThat(headerEventIds).containsExactly(
                "wf:42:gen:1:task-started:1",
                "wf:42:gen:1:task-completed:1",
                "wf:42:gen:1:workflow-completed",
                "wf:42:gen:1:workflow-paused:1"
        );
        assertThat(mdcEventIdsDuringSend).containsExactlyElementsOf(headerEventIds);
        assertThat(headerExecutionIds).containsOnly("777");
        assertThat(headerCorrelationIds).containsOnly("corr-exec-777");

        // Exception during kafkaTemplate.send must still restore previous MDC eventId
        when(template.send(anyString(), anyString(), any())).thenThrow(new RuntimeException("broker down"));
        assertThatThrownBy(() -> producer.publishWorkflowCompleted(WorkflowCompletedEvent.builder()
                .workflowId(42L).workflowName("WF").success(false).generation(1L)
                .eventId("wf:42:gen:1:workflow-completed-fail").build()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID)).isEqualTo("incoming-request-uuid-999");

        // Concurrent threads must not contaminate each other's outgoing X-Event-ID
        KafkaTemplate<String, Object> concurrentTemplate = mock(KafkaTemplate.class);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        when(concurrentTemplate.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            ready.countDown();
            proceed.await(5, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(null);
        });
        ExecutionEventProducer concurrentProducer = new ExecutionEventProducer(concurrentTemplate);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = pool.submit(() -> {
                MDC.put(ExecutionKafkaTracePropagator.MDC_EVENT_ID, "in-1");
                try {
                    concurrentProducer.publishWorkflowCompleted(WorkflowCompletedEvent.builder()
                            .workflowId(1L).workflowName("W1").success(true).generation(1L).eventId("out-1").build());
                    return MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID);
                } finally {
                    MDC.clear();
                }
            });
            Future<String> f2 = pool.submit(() -> {
                MDC.put(ExecutionKafkaTracePropagator.MDC_EVENT_ID, "in-2");
                try {
                    concurrentProducer.publishWorkflowCompleted(WorkflowCompletedEvent.builder()
                            .workflowId(2L).workflowName("W2").success(true).generation(1L).eventId("out-2").build());
                    return MDC.get(ExecutionKafkaTracePropagator.MDC_EVENT_ID);
                } finally {
                    MDC.clear();
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            proceed.countDown();
            assertThat(f1.get(5, TimeUnit.SECONDS)).isEqualTo("in-1");
            assertThat(f2.get(5, TimeUnit.SECONDS)).isEqualTo("in-2");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void executionComposingRecordInterceptor_cleansUpPrimaryWhenDelegateThrowsAndHandlesSubsequentRecords() {
        ExecutionObservabilityKafkaConfig.TraceContextRecordInterceptor<String, String> primary =
                new ExecutionObservabilityKafkaConfig.TraceContextRecordInterceptor<>();
        AtomicBoolean shouldThrow = new AtomicBoolean(true);
        AtomicReference<String> mdcSeenOnSecond = new AtomicReference<>();

        RecordInterceptor<String, String> delegate = new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                if (shouldThrow.get()) {
                    throw new IllegalStateException("exec delegate boom");
                }
                mdcSeenOnSecond.set(MDC.get(ExecutionKafkaTracePropagator.MDC_EXECUTION_ID));
                return record;
            }
        };

        ExecutionObservabilityKafkaConfig.ComposingRecordInterceptor<String, String> composed =
                new ExecutionObservabilityKafkaConfig.ComposingRecordInterceptor<>(primary, delegate);

        ConsumerRecord<String, String> rec1 = new ConsumerRecord<>("workflow-execution", 0, 1L, "10", "v1");
        rec1.headers().add("X-Correlation-ID", "corr-exec-fail".getBytes());
        rec1.headers().add("X-Execution-ID", "555".getBytes());

        assertThatThrownBy(() -> composed.intercept(rec1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("exec delegate boom");

        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_CORRELATION_ID)).isNull();
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EXECUTION_ID)).isNull();

        // Idempotent repeat cleanup
        composed.afterRecord(rec1, null);
        composed.clearThreadState(null);

        // Subsequent record succeeds cleanly
        shouldThrow.set(false);
        ConsumerRecord<String, String> rec2 = new ConsumerRecord<>("workflow-execution", 0, 2L, "20", "v2");
        rec2.headers().add("X-Execution-ID", "666".getBytes());
        composed.intercept(rec2, null);
        assertThat(mdcSeenOnSecond.get()).isEqualTo("666");
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_CORRELATION_ID)).isNull();

        composed.afterRecord(rec2, null);
        assertThat(MDC.get(ExecutionKafkaTracePropagator.MDC_EXECUTION_ID)).isNull();
    }
}
