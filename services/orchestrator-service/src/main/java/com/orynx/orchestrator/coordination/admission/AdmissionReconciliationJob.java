package com.orynx.orchestrator.coordination.admission;

import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Background reconciliation job that reconciles active Redis workflow admission slots
 * against PostgreSQL durable state. Automatically purges orphaned or leaked admission
 * entries for workflows that have completed, failed, paused, or cancelled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdmissionReconciliationJob {

    private final StringRedisTemplate redisTemplate;
    private final WorkflowAdmissionController admissionController;
    private final WorkflowRepository workflowRepository;
    private final RedisCoordinationProperties properties;
    private final RedisCoordinationMetrics metrics;

    @Scheduled(fixedDelayString = "${orynx.admission.reconciliation-interval-seconds:60}000")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        int reconciledCount = 0;
        try {
            Set<String> activeIds = redisTemplate.opsForSet().members(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY);
            if (activeIds == null || activeIds.isEmpty()) {
                metrics.recordOperation("reconcile", true, System.currentTimeMillis() - startTime);
                return;
            }

            for (String rawId : activeIds) {
                try {
                    Long workflowId = Long.parseLong(rawId);
                    Optional<Workflow> workflowOpt = workflowRepository.findById(workflowId);

                    if (workflowOpt.isEmpty()) {
                        log.warn("Reconciliation: workflow {} not found in database. Purging orphaned admission slot.", workflowId);
                        admissionController.release(workflowId, null);
                        reconciledCount++;
                    } else {
                        Workflow wf = workflowOpt.get();
                        WorkflowStatus status = wf.getStatus();
                        if (status == WorkflowStatus.COMPLETED
                                || status == WorkflowStatus.FAILED
                                || status == WorkflowStatus.CANCELLED
                                || status == WorkflowStatus.PAUSED
                                || status == WorkflowStatus.CREATED) {
                            log.info("Reconciliation: workflow {} is in durable status {}. Purging admission slot.", workflowId, status);
                            admissionController.release(workflowId, wf.getUserId());
                            reconciledCount++;
                        }
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
}
