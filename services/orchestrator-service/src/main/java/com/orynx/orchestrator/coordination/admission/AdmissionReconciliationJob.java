package com.orynx.orchestrator.coordination.admission;

import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.lock.DistributedLockService;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Background reconciliation job that reconciles active Redis workflow admission slots
 * against PostgreSQL durable state.
 *
 * Leader-elected via DistributedLockService to guarantee single-instance execution in a cluster.
 * Protects in-flight CREATED workflows with a configurable grace period.
 * Reconstructs active admission state from PostgreSQL on Redis cold restart or data loss.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdmissionReconciliationJob {

    private static final String RECONCILIATION_LOCK_KEY = "admission-reconciliation-leader";
    private static final String RECOVERY_LOCK_KEY = "admission-recovery-leader";

    private final StringRedisTemplate redisTemplate;
    private final WorkflowAdmissionController admissionController;
    private final DistributedLockService distributedLockService;
    private final WorkflowRepository workflowRepository;
    private final RedisCoordinationProperties properties;
    private final RedisCoordinationMetrics metrics;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            recoverIfUninitialized();
        } catch (Exception e) {
            log.error("Failed to execute initial admission state recovery on application startup: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${orynx.admission.reconciliation-interval-seconds:60}000")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }

        Duration lockLease = Duration.ofSeconds(Math.max(properties.getReconciliationIntervalSeconds(), 30));
        distributedLockService.executeWithLock(RECONCILIATION_LOCK_KEY, lockLease, () -> {
            doReconcile();
            return null;
        });
    }

    public void recoverIfUninitialized() {
        Boolean hasKey = redisTemplate.hasKey(RedisWorkflowAdmissionController.INITIALIZED_KEY);
        if (Boolean.TRUE.equals(hasKey)) {
            return;
        }

        distributedLockService.executeWithLock(RECOVERY_LOCK_KEY, Duration.ofMinutes(2), () -> {
            // Double check inside lock
            if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisWorkflowAdmissionController.INITIALIZED_KEY))) {
                return null;
            }

            long startTime = System.currentTimeMillis();
            List<Workflow> runningWorkflows = workflowRepository.findByStatusOrderByIdDesc(WorkflowStatus.RUNNING);
            log.info("Cold-restart recovery: discovered {} RUNNING workflows in PostgreSQL. Reconstructing Redis state...",
                    runningWorkflows.size());

            for (Workflow wf : runningWorkflows) {
                String wfId = String.valueOf(wf.getId());
                redisTemplate.opsForSet().add(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY, wfId);
                long safeUserId = wf.getUserId() != null ? wf.getUserId() : 0L;
                redisTemplate.opsForSet().add(RedisWorkflowAdmissionController.USER_WORKFLOWS_PREFIX + safeUserId, wfId);

                String leaseKey = RedisWorkflowAdmissionController.LEASE_PREFIX + wf.getId();
                long startedAt = wf.getStartedAt() != null ? wf.getStartedAt() : System.currentTimeMillis();
                String leaseValue = safeUserId + ":" + UUID.randomUUID() + ":" + startedAt;
                redisTemplate.opsForValue().set(leaseKey, leaseValue, Duration.ofSeconds(properties.getWorkflowLeaseTtlSeconds()));
            }

            if (runningWorkflows.size() > properties.getMaxGlobalConcurrentWorkflows()) {
                log.error("CRITICAL: Cold recovery found {} RUNNING workflows, exceeding maxGlobalConcurrentWorkflows ({}). " +
                                "All new admissions will be throttled until running capacity drops below limit.",
                        runningWorkflows.size(), properties.getMaxGlobalConcurrentWorkflows());
            }

            redisTemplate.opsForValue().set(
                    RedisWorkflowAdmissionController.INITIALIZED_KEY,
                    "true",
                    Duration.ofHours(24)
            );

            long duration = System.currentTimeMillis() - startTime;
            metrics.updateActiveWorkflows(runningWorkflows.size(), properties.getMaxGlobalConcurrentWorkflows());
            metrics.recordOperation("recovery", true, duration);
            log.info("Cold-restart admission state recovery completed: enrolled {} workflows in {}ms",
                    runningWorkflows.size(), duration);
            return null;
        });
    }

    private void doReconcile() {
        // 1. Check if Redis lost state while cluster was running
        recoverIfUninitialized();

        long startTime = System.currentTimeMillis();
        int reconciledCount = 0;
        try {
            Set<String> activeIds = redisTemplate.opsForSet().members(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY);
            if (activeIds == null || activeIds.isEmpty()) {
                metrics.recordOperation("reconcile", true, System.currentTimeMillis() - startTime);
                return;
            }

            long now = System.currentTimeMillis();
            long gracePeriodMs = properties.getInflightGracePeriodSeconds() * 1000L;

            for (String rawId : activeIds) {
                try {
                    Long workflowId = Long.parseLong(rawId);
                    Optional<Workflow> workflowOpt = workflowRepository.findById(workflowId);

                    if (workflowOpt.isEmpty()) {
                        String leaseVal = redisTemplate.opsForValue().get(RedisWorkflowAdmissionController.LEASE_PREFIX + workflowId);
                        long admittedAtMs = extractAdmittedAtMs(leaseVal);
                        if (leaseVal != null && (now - admittedAtMs) < gracePeriodMs) {
                            log.debug("Reconciliation: workflow {} not yet committed to DB but lease is fresh ({}ms elapsed). Retaining slot.",
                                    workflowId, (now - admittedAtMs));
                        } else {
                            log.warn("Reconciliation: workflow {} not found in database and outside grace period. Purging orphaned admission slot.", workflowId);
                            admissionController.release(workflowId, null);
                            reconciledCount++;
                        }
                    } else {
                        Workflow wf = workflowOpt.get();
                        WorkflowStatus status = wf.getStatus();

                        if (status == WorkflowStatus.CREATED) {
                            String leaseVal = redisTemplate.opsForValue().get(RedisWorkflowAdmissionController.LEASE_PREFIX + workflowId);
                            long admittedAtMs = extractAdmittedAtMs(leaseVal);
                            if (leaseVal != null && (now - admittedAtMs) < gracePeriodMs) {
                                log.debug("Reconciliation: workflow {} is CREATED and within in-flight grace period ({}ms elapsed). Retaining slot.",
                                        workflowId, (now - admittedAtMs));
                            } else {
                                log.warn("Reconciliation: workflow {} is CREATED and exceeded grace period ({}ms elapsed). Purging abandoned admission slot.",
                                        workflowId, (now - admittedAtMs));
                                admissionController.release(workflowId, wf.getUserId());
                                reconciledCount++;
                            }
                        } else if (status == WorkflowStatus.COMPLETED
                                || status == WorkflowStatus.FAILED
                                || status == WorkflowStatus.CANCELLED
                                || status == WorkflowStatus.PAUSED) {
                            log.info("Reconciliation: workflow {} is in durable status {}. Purging admission slot.", workflowId, status);
                            admissionController.release(workflowId, wf.getUserId());
                            reconciledCount++;
                        }
                        // If RUNNING: valid active workflow, retain slot.
                    }
                } catch (NumberFormatException nfe) {
                    log.warn("Reconciliation: found non-numeric active workflow ID in Redis: {}", rawId);
                    redisTemplate.opsForSet().remove(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY, rawId);
                }
            }
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("reconcile", true, duration);
            if (reconciledCount > 0) {
                log.info("Admission reconciliation completed: purged {} stale admission slots in {}ms", reconciledCount, duration);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("reconcile", false, duration);
            log.error("Error during Redis admission reconciliation: {}", e.getMessage(), e);
        }
    }

    private long extractAdmittedAtMs(String leaseVal) {
        if (leaseVal == null || leaseVal.isBlank()) {
            return 0L;
        }
        String[] parts = leaseVal.split(":");
        if (parts.length >= 3) {
            try {
                return Long.parseLong(parts[2]);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }
}
