package com.orynx.orchestrator.coordination.admission;

import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Production Redis-backed implementation of distributed workflow admission control.
 * Coordinates execution slots globally and per-user using atomic Lua scripts.
 * Uses Redis cluster hash tags {admission} to prevent CROSSSLOT errors.
 */
@Service
@Slf4j
public class RedisWorkflowAdmissionController implements WorkflowAdmissionController {

    public static final String KEY_PREFIX = "orynx:{admission}:";
    public static final String GLOBAL_ACTIVE_KEY = KEY_PREFIX + "active_workflows";
    public static final String USER_WORKFLOWS_PREFIX = KEY_PREFIX + "user_workflows:";
    public static final String LEASE_PREFIX = KEY_PREFIX + "lease:";
    public static final String INITIALIZED_KEY = KEY_PREFIX + "initialized";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> admitWorkflowScript;
    private final RedisScript<List> releaseWorkflowScript;
    private final RedisCoordinationProperties properties;
    private final RedisCoordinationMetrics metrics;

    private com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry faultRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisWorkflowAdmissionController(StringRedisTemplate redisTemplate,
                                           RedisScript<List> admitWorkflowScript,
                                           RedisScript<List> releaseWorkflowScript,
                                           RedisCoordinationProperties properties,
                                           RedisCoordinationMetrics metrics,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry faultRegistry) {
        this.redisTemplate = redisTemplate;
        this.admitWorkflowScript = admitWorkflowScript;
        this.releaseWorkflowScript = releaseWorkflowScript;
        this.properties = properties;
        this.metrics = metrics;
        this.faultRegistry = faultRegistry;
    }

    public RedisWorkflowAdmissionController(StringRedisTemplate redisTemplate,
                                           RedisScript<List> admitWorkflowScript,
                                           RedisScript<List> releaseWorkflowScript,
                                           RedisCoordinationProperties properties,
                                           RedisCoordinationMetrics metrics) {
        this(redisTemplate, admitWorkflowScript, releaseWorkflowScript, properties, metrics, null);
    }

    public void setFaultRegistry(com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry faultRegistry) {
        this.faultRegistry = faultRegistry;
    }

    @Override
    public AdmissionDecision tryAdmit(Long workflowId, Long userId) {
        if (!properties.isEnabled()) {
            log.debug("Workflow admission control is disabled. Bypassing admission for workflow {}", workflowId);
            return AdmissionDecision.admitted("ADMISSION_DISABLED", 0, 0, "disabled-token");
        }

        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId cannot be null for admission");
        }

        // Check if an isolated resilience fault injection is active via a single atomic snapshot
        if (faultRegistry != null) {
            java.util.Optional<com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry.ActiveFault> activeRedisFault =
                    faultRegistry.getActiveRedisFault();
            if (activeRedisFault.isPresent()) {
                com.orynx.orchestrator.resilience.adapter.ResilienceFaultRegistry.ActiveFault fault = activeRedisFault.get();
                AdmissionFailurePolicy activePolicy = fault.getAdmissionFailurePolicy() != null
                        ? fault.getAdmissionFailurePolicy()
                        : properties.getFailurePolicy();
                if (activePolicy == AdmissionFailurePolicy.FAIL_OPEN) {
                    log.warn("Resilience fault injected (FAIL_OPEN, experiment={}): allowing workflow {} to proceed",
                            fault.getExperimentId(), workflowId);
                    metrics.recordAdmission("accepted", "fail_open");
                    return AdmissionDecision.failOpenBypassed("FAIL_OPEN_REDIS_INJECTED_FAULT");
                } else {
                    metrics.recordAdmission("error", "redis_unavailable");
                    throw WorkflowAdmissionException.redisUnavailable("Simulated Redis outage by resilience experiment", properties.getRetryAfterSeconds());
                }
            }
        }

        long safeUserId = userId != null ? userId : 0L;
        String userKey = USER_WORKFLOWS_PREFIX + safeUserId;
        String leaseKey = LEASE_PREFIX + workflowId;
        String leaseToken = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        List<String> keys = List.of(GLOBAL_ACTIVE_KEY, userKey, leaseKey);
        Object[] args = new Object[]{
                String.valueOf(workflowId),
                String.valueOf(properties.getMaxGlobalConcurrentWorkflows()),
                String.valueOf(properties.getMaxUserConcurrentWorkflows()),
                String.valueOf(properties.getWorkflowLeaseTtlSeconds()),
                leaseToken,
                String.valueOf(safeUserId),
                String.valueOf(now)
        };

        long startTime = System.currentTimeMillis();
        try {
            @SuppressWarnings("unchecked")
            List<Object> result = redisTemplate.execute(admitWorkflowScript, keys, args);
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("admit", true, duration);

            if (result == null || result.isEmpty()) {
                throw new IllegalStateException("Empty result returned from admit_workflow.lua script");
            }

            int success = Integer.parseInt(String.valueOf(result.get(0)));
            String reason = String.valueOf(result.get(1));
            long currentGlobal = Long.parseLong(String.valueOf(result.get(2)));
            long currentUser = Long.parseLong(String.valueOf(result.get(3)));

            metrics.updateActiveWorkflows(currentGlobal, properties.getMaxGlobalConcurrentWorkflows());

            if (success == 1) {
                log.info("Workflow {} admitted successfully (reason={}, globalCount={}, userCount={})",
                        workflowId, reason, currentGlobal, currentUser);
                metrics.recordAdmission("accepted", reason);
                return AdmissionDecision.admitted(reason, currentGlobal, currentUser, leaseToken);
            } else {
                log.warn("Workflow {} admission rejected: reason={}, globalCount={}, userCount={}",
                        workflowId, reason, currentGlobal, currentUser);
                metrics.recordAdmission("rejected", reason);

                if ("GLOBAL_LIMIT_EXCEEDED".equalsIgnoreCase(reason)) {
                    throw WorkflowAdmissionException.globalLimitExceeded(
                            currentGlobal,
                            properties.getMaxGlobalConcurrentWorkflows(),
                            properties.getRetryAfterSeconds()
                    );
                } else if ("USER_LIMIT_EXCEEDED".equalsIgnoreCase(reason)) {
                    throw WorkflowAdmissionException.userLimitExceeded(
                            currentUser,
                            properties.getMaxUserConcurrentWorkflows(),
                            safeUserId,
                            properties.getRetryAfterSeconds()
                    );
                } else {
                    throw new WorkflowAdmissionException(
                            "Workflow admission rejected: " + reason,
                            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                            reason,
                            properties.getRetryAfterSeconds()
                    );
                }
            }
        } catch (WorkflowAdmissionException wae) {
            // Rethrow expected admission rejection
            throw wae;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("admit", false, duration);
            log.error("Redis admission check failed for workflow {}: {}", workflowId, e.getMessage(), e);

            if (properties.getFailurePolicy() == AdmissionFailurePolicy.FAIL_OPEN) {
                log.warn("FAIL_OPEN policy active: allowing workflow {} to proceed despite Redis failure", workflowId);
                metrics.recordAdmission("accepted", "fail_open");
                return AdmissionDecision.failOpenBypassed("FAIL_OPEN_REDIS_ERROR: " + e.getMessage());
            } else {
                metrics.recordAdmission("error", "redis_unavailable");
                throw WorkflowAdmissionException.redisUnavailable(e.getMessage(), properties.getRetryAfterSeconds());
            }
        }
    }

    @Override
    public void release(Long workflowId, Long userId) {
        if (!properties.isEnabled() || workflowId == null) {
            return;
        }

        long safeUserId = userId != null ? userId : 0L;
        String userKey = USER_WORKFLOWS_PREFIX + safeUserId;
        String leaseKey = LEASE_PREFIX + workflowId;

        List<String> keys = List.of(GLOBAL_ACTIVE_KEY, userKey, leaseKey);
        Object[] args = new Object[]{String.valueOf(workflowId)};

        long startTime = System.currentTimeMillis();
        try {
            @SuppressWarnings("unchecked")
            List<Object> result = redisTemplate.execute(releaseWorkflowScript, keys, args);
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("release", true, duration);

            if (result != null && result.size() >= 3) {
                long currentGlobal = Long.parseLong(String.valueOf(result.get(2)));
                metrics.updateActiveWorkflows(currentGlobal, properties.getMaxGlobalConcurrentWorkflows());
            }
            log.info("Released workflow admission slot for workflow {} (user={})", workflowId, safeUserId);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("release", false, duration);
            log.error("Failed to release Redis admission slot for workflow {}: {}", workflowId, e.getMessage(), e);
            // Non-fatal: do not abort workflow database transaction on release failure
        }
    }

    @Override
    public boolean isAdmitted(Long workflowId) {
        if (workflowId == null) return false;
        try {
            Boolean member = redisTemplate.opsForSet().isMember(GLOBAL_ACTIVE_KEY, String.valueOf(workflowId));
            return Boolean.TRUE.equals(member);
        } catch (Exception e) {
            log.warn("Failed to check admission status in Redis for workflow {}: {}", workflowId, e.getMessage());
            return false;
        }
    }

    @Override
    public long getActiveGlobalCount() {
        try {
            Long size = redisTemplate.opsForSet().size(GLOBAL_ACTIVE_KEY);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.warn("Failed to get active global count from Redis: {}", e.getMessage());
            return 0L;
        }
    }

    @Override
    public long getActiveUserCount(Long userId) {
        if (userId == null) return 0L;
        try {
            Long size = redisTemplate.opsForSet().size(USER_WORKFLOWS_PREFIX + userId);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.warn("Failed to get active user count for user {} from Redis: {}", userId, e.getMessage());
            return 0L;
        }
    }
}
