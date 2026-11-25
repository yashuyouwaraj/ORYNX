package com.orynx.execution.observability;

import com.orynx.execution.coordination.ExecutionCoordinationService;
import com.orynx.execution.execution.ActiveExecutionTracker;
import com.orynx.execution.execution.WorkflowExecutionDispatcher;
import com.orynx.execution.execution.WorkflowExecutionEngine;
import com.orynx.execution.worker.ExecutionWorkerProperties;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
            AtomicReference<String> observedCorrInSecondRun = new AtomicReference<>();
            CountDownLatch firstDone = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);

            doAnswer(inv -> {
                WorkflowExecutionRequestEvent ev = inv.getArgument(0);
                if (ev.getWorkflowId() == 101L) {
                    observedCorrInFirstRun.set(MDC.get("correlationId"));
                    observedExecInFirstRun.set(MDC.get("executionId"));
                    firstDone.countDown();
                } else {
                    observedCorrInSecondRun.set(MDC.get("correlationId"));
                    secondDone.countDown();
                }
                return null;
            }).when(engine).execute(any(), anyLong(), anyString());

            // First dispatch WITH correlationId in caller MDC
            MDC.put("correlationId", "corr-worker-101");
            dispatcher.dispatch(WorkflowExecutionRequestEvent.builder()
                    .workflowId(101L)
                    .workflowName("WF-101")
                    .build(), 2L);
            assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observedCorrInFirstRun.get()).isEqualTo("corr-worker-101");
            assertThat(observedExecInFirstRun.get()).isEqualTo("wf-101-gen-2");

            // Second dispatch on the SAME single worker thread WITHOUT correlationId in caller MDC
            MDC.clear();
            dispatcher.dispatch(WorkflowExecutionRequestEvent.builder()
                    .workflowId(202L)
                    .workflowName("WF-202")
                    .build(), 1L);
            assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
            // Must NOT leak corr-worker-101 onto the reused worker thread
            assertThat(observedCorrInSecondRun.get()).isNull();
        } finally {
            executor.shutdown();
        }
    }
}
