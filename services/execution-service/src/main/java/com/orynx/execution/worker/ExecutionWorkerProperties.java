package com.orynx.execution.worker;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "orynx.execution.worker")
public class ExecutionWorkerProperties {

    /**
     * Core number of worker threads for executing workflows.
     */
    private int corePoolSize = 4;

    /**
     * Maximum number of worker threads.
     */
    private int maxPoolSize = 8;

    /**
     * Bounded queue capacity for waiting workflow executions before triggering backpressure.
     */
    private int queueCapacity = 50;

    /**
     * Keep-alive time in seconds for idle non-core threads.
     */
    private int keepAliveSeconds = 60;

    /**
     * Maximum time in seconds to wait for worker threads to terminate during shutdown.
     */
    private int awaitTerminationSeconds = 30;

    /**
     * Distributed execution lease duration in seconds before considering a worker crashed.
     */
    private long leaseDurationSeconds = 300L;

    /**
     * Heartbeat interval in seconds for extending the lease of an actively running worker.
     */
    private long heartbeatIntervalSeconds = 30L;

    /**
     * Whether the background recovery scanner for abandoned executions is enabled.
     */
    private boolean recoveryEnabled = true;

    /**
     * Interval in seconds between recovery scanner runs.
     */
    private long recoveryIntervalSeconds = 60L;

    /**
     * Maximum number of automatic recovery attempts before marking an abandoned workflow FAILED.
     */
    private int maxRecoveryAttempts = 3;
}
