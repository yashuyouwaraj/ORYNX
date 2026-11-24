package com.orynx.orchestrator.coordination.admission;

/**
 * Result of an atomic workflow admission evaluation in Redis.
 */
public record AdmissionDecision(
        boolean admitted,
        String reason,
        long currentGlobal,
        long currentUser,
        String leaseToken,
        boolean bypassedDueToFailure
) {
    public static AdmissionDecision admitted(String reason, long global, long user, String token) {
        return new AdmissionDecision(true, reason, global, user, token, false);
    }

    public static AdmissionDecision rejected(String reason, long global, long user) {
        return new AdmissionDecision(false, reason, global, user, null, false);
    }

    public static AdmissionDecision failOpenBypassed(String reason) {
        return new AdmissionDecision(true, reason, 0, 0, "fail-open-token", true);
    }
}
