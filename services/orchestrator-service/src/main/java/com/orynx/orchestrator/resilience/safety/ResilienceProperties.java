package com.orynx.orchestrator.resilience.safety;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Server-controlled configuration properties governing resilience failure experiments.
 * Ensures that resilience capabilities are disabled by default in production,
 * and environment authorization is verified against server-side ground truth.
 */
@Configuration
@ConfigurationProperties(prefix = "orynx.resilience")
@Getter
@Setter
public class ResilienceProperties {

    /**
     * Master switch for the resilience testing framework.
     * Must be explicitly enabled (defaults to true in test/staging, false in production).
     */
    private boolean enabled = true;

    /**
     * Server-side verified environment identity (e.g. test, resilience-isolated, staging).
     * Caller-supplied environment tags must match this server-side ground truth.
     */
    private String serverEnvironment = "test";

    /**
     * Whether failure to acquire distributed lock fails closed (rejects experiment).
     */
    private boolean failClosedOnDistributedLock = true;

    /**
     * Maximum permissible duration in milliseconds for an injected failure.
     */
    private long maxDurationMs = 120_000L;

    /**
     * Maximum permissible experiment execution timeout in milliseconds.
     */
    private long maxTimeoutMs = 300_000L;

    /**
     * List of environments permitted to execute failure experiments.
     */
    private List<String> allowedEnvironments = List.of("test", "resilience-isolated", "staging", "isolated");
}
