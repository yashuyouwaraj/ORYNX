package com.orynx.orchestrator.resilience.safety;

/**
 * Thrown when an experiment definition fails safety validation or violates containment boundaries.
 */
public class SafetyValidationException extends RuntimeException {

    public SafetyValidationException(String message) {
        super(message);
    }

    public SafetyValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
