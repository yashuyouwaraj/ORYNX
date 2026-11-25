package com.orynx.orchestrator.common.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.MDC;

/**
 * Helper for attaching bounded, non-sensitive workflow lifecycle attributes to active OpenTelemetry spans
 * and scoping workflow/execution MDC fields with guaranteed restoration to prevent thread-pool leaks.
 */
public final class WorkflowTracingHelper {

    private static final String INSTRUMENTATION_SCOPE = "com.orynx.orchestrator.workflow";

    private WorkflowTracingHelper() {
    }

    public static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * Tags the active OpenTelemetry span with workflow lifecycle attributes without mutating MDC.
     */
    public static void tagSpanLifecycle(Long workflowId, Long executionId, Long generation, String transition) {
        Span span = Span.current();
        if (span != null && span.getSpanContext().isValid()) {
            if (workflowId != null) {
                span.setAttribute("orynx.workflow.id", String.valueOf(workflowId));
            }
            if (executionId != null) {
                span.setAttribute("orynx.execution.id", String.valueOf(executionId));
            }
            if (generation != null) {
                span.setAttribute("orynx.execution.generation", generation);
            }
            if (transition != null && !transition.isBlank()) {
                span.setAttribute("orynx.workflow.state_transition", transition);
            }
            String corrId = MDC.get(KafkaTraceContextPropagator.MDC_CORRELATION_ID);
            if (corrId != null) {
                span.setAttribute("orynx.correlation.id", corrId);
            }
        }
    }

    /**
     * Tags the active span and returns an AutoCloseable MDC scope that restores prior workflowId/executionId
     * values on close, preventing MDC leakage across non-HTTP threads and thread pools.
     */
    public static MdcScope scopedWorkflowLifecycle(Long workflowId, Long executionId, Long generation, String transition) {
        tagSpanLifecycle(workflowId, executionId, generation, transition);
        String prevWorkflowId = MDC.get(KafkaTraceContextPropagator.MDC_WORKFLOW_ID);
        String prevExecutionId = MDC.get(KafkaTraceContextPropagator.MDC_EXECUTION_ID);
        if (workflowId != null) {
            MDC.put(KafkaTraceContextPropagator.MDC_WORKFLOW_ID, String.valueOf(workflowId));
        }
        if (executionId != null) {
            MDC.put(KafkaTraceContextPropagator.MDC_EXECUTION_ID, String.valueOf(executionId));
        }
        return new MdcScope(prevWorkflowId, prevExecutionId);
    }

    public static final class MdcScope implements AutoCloseable {
        private final String previousWorkflowId;
        private final String previousExecutionId;

        private MdcScope(String previousWorkflowId, String previousExecutionId) {
            this.previousWorkflowId = previousWorkflowId;
            this.previousExecutionId = previousExecutionId;
        }

        @Override
        public void close() {
            restore(KafkaTraceContextPropagator.MDC_WORKFLOW_ID, previousWorkflowId);
            restore(KafkaTraceContextPropagator.MDC_EXECUTION_ID, previousExecutionId);
        }

        private static void restore(String key, String previousValue) {
            if (previousValue == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previousValue);
            }
        }
    }
}
