package com.orynx.execution.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Propagates W3C Trace Context and sanitized OrynX correlation headers across Kafka boundaries
 * in execution-service.
 */
public final class ExecutionKafkaTracePropagator {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-ID";
    public static final String HEADER_REQUEST_ID = "X-Request-ID";
    public static final String HEADER_WORKFLOW_ID = "X-Workflow-ID";
    public static final String HEADER_EXECUTION_ID = "X-Execution-ID";
    public static final String HEADER_EVENT_ID = "X-Event-ID";
    public static final String HEADER_TRACEPARENT = "traceparent";

    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_WORKFLOW_ID = "workflowId";
    public static final String MDC_EXECUTION_ID = "executionId";
    public static final String MDC_EVENT_ID = "eventId";
    public static final String MDC_ERROR_CATEGORY = "errorCategory";
    public static final String MDC_WORKER_ID = "workerId";
    public static final String MDC_ATTEMPT_NUMBER = "attemptNumber";

    private static final TextMapSetter<Headers> KAFKA_HEADER_SETTER = (carrier, key, value) -> {
        if (carrier == null || key == null || value == null) {
            return;
        }
        carrier.remove(key);
        carrier.add(key, value.getBytes(StandardCharsets.UTF_8));
    };

    private static final TextMapGetter<Headers> KAFKA_HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            List<String> keys = new ArrayList<>();
            if (carrier != null) {
                for (Header h : carrier) {
                    keys.add(h.key());
                }
            }
            return keys;
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            Header header = carrier.lastHeader(key);
            if (header == null || header.value() == null) {
                return null;
            }
            return new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    private ExecutionKafkaTracePropagator() {
    }

    public static void inject(Headers headers, Context otelContext) {
        if (headers == null) {
            return;
        }
        Context ctx = otelContext != null ? otelContext : Context.current();
        W3CTraceContextPropagator.getInstance().inject(ctx, headers, KAFKA_HEADER_SETTER);

        putSanitizedHeaderIfAbsent(headers, HEADER_CORRELATION_ID, MDC.get(MDC_CORRELATION_ID));
        putSanitizedHeaderIfAbsent(headers, HEADER_REQUEST_ID, MDC.get(MDC_REQUEST_ID));
        putSanitizedHeaderIfAbsent(headers, HEADER_WORKFLOW_ID, MDC.get(MDC_WORKFLOW_ID));
        putSanitizedHeaderIfAbsent(headers, HEADER_EXECUTION_ID, MDC.get(MDC_EXECUTION_ID));
        putSanitizedHeaderIfAbsent(headers, HEADER_EVENT_ID, MDC.get(MDC_EVENT_ID));
    }

    public static Context extract(Headers headers) {
        if (headers == null) {
            return Context.root();
        }
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), headers, KAFKA_HEADER_GETTER);
    }

    /**
     * Resolves the canonical execution ID for a workflow execution request:
     * 1. Canonical event.executionId from orchestrator (primary source of truth).
     * 2. Propagated X-Execution-ID already in MDC (secondary).
     * 3. Explicit legacy fallback "wf-{workflowId}-gen-{generation}" when neither is present.
     */
    public static String resolveCanonicalExecutionId(
            com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent event,
            Long generation
    ) {
        if (event != null && event.getExecutionId() != null) {
            return String.valueOf(event.getExecutionId());
        }
        String mdcExecId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(MDC.get(MDC_EXECUTION_ID));
        if (mdcExecId != null) {
            return mdcExecId;
        }
        Long workflowId = event != null ? event.getWorkflowId() : null;
        long effectiveGen = generation != null ? generation : (event != null && event.getGeneration() != null ? event.getGeneration() : 1L);
        return "wf-" + workflowId + "-gen-" + effectiveGen;
    }

    public static void populateMdcFromHeaders(Headers headers, Context extractedContext) {
        if (headers != null) {
            String correlationId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_CORRELATION_ID));
            String requestId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_REQUEST_ID));
            String workflowId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_WORKFLOW_ID));
            String executionId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_EXECUTION_ID));
            String eventId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_EVENT_ID));

            if (correlationId == null) {
                correlationId = requestId;
            }
            if (correlationId != null) {
                MDC.put(MDC_CORRELATION_ID, correlationId);
            }
            if (requestId != null) {
                MDC.put(MDC_REQUEST_ID, requestId);
            }
            if (workflowId != null) {
                MDC.put(MDC_WORKFLOW_ID, workflowId);
            }
            if (executionId != null) {
                MDC.put(MDC_EXECUTION_ID, executionId);
            }
            if (eventId != null) {
                MDC.put(MDC_EVENT_ID, eventId);
            }
        }
        SpanContext spanContext = extractedContext != null
                ? Span.fromContext(extractedContext).getSpanContext()
                : SpanContext.getInvalid();
        if (!spanContext.isValid() && headers != null) {
            spanContext = Span.fromContext(extract(headers)).getSpanContext();
        }
        if (spanContext.isValid()) {
            MDC.put(MDC_TRACE_ID, spanContext.getTraceId());
            MDC.put(MDC_SPAN_ID, spanContext.getSpanId());
        }
    }

    /**
     * Enriches an active span (e.g., created by the OTel Java agent's Kafka instrumentation)
     * with sanitized OrynX correlation attributes without creating a duplicate consumer span.
     */
    public static void enrichSpanFromHeaders(Span span, Headers headers, String topic) {
        if (span == null || !span.getSpanContext().isValid()) {
            return;
        }
        if (topic != null && !topic.isBlank()) {
            span.setAttribute("orynx.kafka.topic", topic);
        }
        if (headers != null) {
            String correlationId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_CORRELATION_ID));
            String workflowId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_WORKFLOW_ID));
            String executionId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_EXECUTION_ID));
            String eventId = ExecutionObservabilitySanitizer.sanitizeCorrelationId(getHeader(headers, HEADER_EVENT_ID));
            if (correlationId != null) {
                span.setAttribute("orynx.correlation.id", correlationId);
            }
            if (workflowId != null) {
                span.setAttribute("orynx.workflow.id", workflowId);
            }
            if (executionId != null) {
                span.setAttribute("orynx.execution.id", executionId);
            }
            if (eventId != null) {
                span.setAttribute("orynx.event.id", eventId);
            }
        }
    }

    public static Span startConsumerSpan(Tracer tracer, String operation, Headers headers, String topic) {
        Context parent = extract(headers);
        Span span = tracer.spanBuilder(operation)
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination.name", topic != null ? topic : "unknown")
                .startSpan();

        enrichSpanFromHeaders(span, headers, topic);
        return span;
    }

    public static void clearKafkaMdc() {
        MDC.remove(MDC_CORRELATION_ID);
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_WORKFLOW_ID);
        MDC.remove(MDC_EXECUTION_ID);
        MDC.remove(MDC_EVENT_ID);
        MDC.remove(MDC_ERROR_CATEGORY);
        MDC.remove(MDC_WORKER_ID);
        MDC.remove(MDC_ATTEMPT_NUMBER);
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
    }

    public static String getHeader(Headers headers, String key) {
        return KAFKA_HEADER_GETTER.get(headers, key);
    }

    private static void putSanitizedHeaderIfAbsent(Headers headers, String key, String rawValue) {
        if (headers.lastHeader(key) != null) {
            return;
        }
        String safe = ExecutionObservabilitySanitizer.sanitizeCorrelationId(rawValue);
        if (safe != null) {
            headers.add(key, safe.getBytes(StandardCharsets.UTF_8));
        }
    }
}
