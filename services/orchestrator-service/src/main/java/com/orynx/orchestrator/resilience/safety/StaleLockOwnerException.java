package com.orynx.orchestrator.resilience.safety;

/**
 * Thrown when an orchestrator node detects that its distributed resilience lock lease
 * has expired or been acquired by another cluster node, preventing stale operations.
 */
public class StaleLockOwnerException extends SafetyValidationException {

    public StaleLockOwnerException(String message) {
        super(message);
    }
}
