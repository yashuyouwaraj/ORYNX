package com.orynx.orchestrator.coordination.admission;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Exception thrown when workflow execution admission is rejected due to cluster capacity limits,
 * per-user concurrency quotas, or coordination infrastructure failure under a FAIL_CLOSED policy.
 */
@Getter
public class WorkflowAdmissionException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String reason;
    private final int retryAfterSeconds;

    public WorkflowAdmissionException(String message, HttpStatus httpStatus, String reason, int retryAfterSeconds) {
        super(message);
        this.httpStatus = httpStatus;
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static WorkflowAdmissionException globalLimitExceeded(long current, int max, int retryAfter) {
        return new WorkflowAdmissionException(
                String.format("Workflow admission rejected: global execution concurrency limit reached (%d/%d)", current, max),
                HttpStatus.TOO_MANY_REQUESTS,
                "GLOBAL_LIMIT_EXCEEDED",
                retryAfter
        );
    }

    public static WorkflowAdmissionException userLimitExceeded(long current, int max, Long userId, int retryAfter) {
        return new WorkflowAdmissionException(
                String.format("Workflow admission rejected: user execution quota exceeded (%d/%d) for user %d", current, max, userId),
                HttpStatus.TOO_MANY_REQUESTS,
                "USER_LIMIT_EXCEEDED",
                retryAfter
        );
    }

    public static WorkflowAdmissionException redisUnavailable(String detail, int retryAfter) {
        return new WorkflowAdmissionException(
                "Workflow admission temporarily unavailable: distributed coordination service unreachable. " + detail,
                HttpStatus.SERVICE_UNAVAILABLE,
                "REDIS_UNAVAILABLE",
                retryAfter
        );
    }
}
