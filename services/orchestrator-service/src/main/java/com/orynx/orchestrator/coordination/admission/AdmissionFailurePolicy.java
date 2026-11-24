package com.orynx.orchestrator.coordination.admission;

/**
 * Strategy applied when Redis coordination infrastructure is unavailable or times out.
 */
public enum AdmissionFailurePolicy {
    /**
     * Rejects admission attempt with a retryable error (HTTP 503) to prevent overloading the backend.
     */
    FAIL_CLOSED,

    /**
     * Bypasses Redis admission control and allows workflow execution to proceed when Redis is down.
     */
    FAIL_OPEN
}
