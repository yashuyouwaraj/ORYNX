package com.orynx.orchestrator.coordination.config;

import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "orynx.admission")
@Getter
@Setter
public class RedisCoordinationProperties {

    /**
     * Whether distributed admission control via Redis is enabled.
     */
    private boolean enabled = true;

    /**
     * Maximum global concurrent admitted workflows across the distributed cluster.
     * 0 or negative indicates no global limit.
     */
    private int maxGlobalConcurrentWorkflows = 100;

    /**
     * Maximum concurrent admitted workflows per user.
     * 0 or negative indicates no per-user limit.
     */
    private int maxUserConcurrentWorkflows = 10;

    /**
     * Lease duration for an admitted workflow in seconds.
     * Provides automatic TTL expiration in case of ungraceful worker termination.
     */
    private int workflowLeaseTtlSeconds = 3600;

    /**
     * Failure policy when Redis is unreachable or times out during admission.
     * FAIL_CLOSED: Rejects workflow execution with HTTP 503 / retry-after to protect cluster stability.
     * FAIL_OPEN: Logs warning and allows execution to proceed.
     */
    private AdmissionFailurePolicy failurePolicy = AdmissionFailurePolicy.FAIL_CLOSED;

    /**
     * Suggested retry-after period in seconds returned to clients on rejection/backpressure.
     */
    private int retryAfterSeconds = 5;

    /**
     * Interval in seconds for the background reconciliation job that reconciles
     * active Redis admission entries against PostgreSQL workflow ground truth.
     */
    private int reconciliationIntervalSeconds = 60;

    /**
     * Default lock lease duration for distributed locks.
     */
    private Duration defaultLockLease = Duration.ofSeconds(30);
}
