package com.orynx.execution.coordination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionCoordinationService {

    private final WorkflowExecutionCoordinationRepository repository;

    public enum RecoveryOutcome {
        RECLAIMED,
        FAILED_EXCEEDED_ATTEMPTS,
        INELIGIBLE
    }

    public record RecoveryResult(RecoveryOutcome outcome, WorkflowExecutionCoordination coordination) {}

    @Transactional
    public ExecutionLeaseAcquisitionResult tryAcquireLease(
            Long workflowId,
            String workflowName,
            String instanceId,
            long leaseDurationSeconds
    ) {
        return tryAcquireLease(workflowId, workflowName, instanceId, leaseDurationSeconds, null);
    }

    @Transactional
    public ExecutionLeaseAcquisitionResult tryAcquireLease(
            Long workflowId,
            String workflowName,
            String instanceId,
            long leaseDurationSeconds,
            String payloadJson
    ) {
        return tryAcquireLease(workflowId, workflowName, instanceId, leaseDurationSeconds, payloadJson, false);
    }

    @Transactional
    public ExecutionLeaseAcquisitionResult tryAcquireLease(
            Long workflowId,
            String workflowName,
            String instanceId,
            long leaseDurationSeconds,
            String payloadJson,
            boolean isResumeRequest
    ) {
        try {
            return doAcquireLease(workflowId, workflowName, instanceId, leaseDurationSeconds, payloadJson, isResumeRequest);
        } catch (DataIntegrityViolationException e) {
            log.warn(
                    "Concurrent lease creation race detected for workflow {} on instance {}. Treating as duplicate active lease.",
                    workflowId,
                    instanceId
            );
            return repository.findById(workflowId)
                    .map(ExecutionLeaseAcquisitionResult::duplicateActive)
                    .orElseGet(() -> new ExecutionLeaseAcquisitionResult(
                            ExecutionLeaseAcquisitionResult.Status.DUPLICATE_ACTIVE,
                            "Concurrent lease creation race collision",
                            null
                    ));
        }
    }

    private ExecutionLeaseAcquisitionResult doAcquireLease(
            Long workflowId,
            String workflowName,
            String instanceId,
            long leaseDurationSeconds,
            String payloadJson,
            boolean isResumeRequest
    ) {
        Optional<WorkflowExecutionCoordination> existingOpt = repository.findByWorkflowIdForUpdate(workflowId);

        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plusSeconds(leaseDurationSeconds);

        if (existingOpt.isEmpty()) {
            WorkflowExecutionCoordination coordination = WorkflowExecutionCoordination.builder()
                    .workflowId(workflowId)
                    .workflowName(workflowName)
                    .status(ExecutionCoordinationStatus.DISPATCHED)
                    .instanceId(instanceId)
                    .acquiredAt(now)
                    .updatedAt(now)
                    .leaseExpiresAt(leaseExpiresAt)
                    .generation(1L)
                    .recoveryAttempts(0)
                    .payloadJson(payloadJson)
                    .build();

            WorkflowExecutionCoordination saved = repository.save(coordination);
            log.info("Acquired initial execution lease for workflow {} (gen=1) on instance {}", workflowId, instanceId);
            return ExecutionLeaseAcquisitionResult.acquired(saved);
        }

        WorkflowExecutionCoordination coordination = existingOpt.get();
        ExecutionCoordinationStatus currentStatus = coordination.getStatus();

        if (currentStatus == ExecutionCoordinationStatus.COMPLETED ||
                currentStatus == ExecutionCoordinationStatus.CANCELLED) {
            log.warn(
                    "Workflow {} is already terminal ({}) on instance {}. Rejecting duplicate request.",
                    workflowId,
                    currentStatus,
                    coordination.getInstanceId()
            );
            return ExecutionLeaseAcquisitionResult.duplicateTerminal(coordination);
        }

        long nextGeneration = (coordination.getGeneration() != null ? coordination.getGeneration() : 1L) + 1L;

        if (currentStatus == ExecutionCoordinationStatus.PAUSED) {
            if (!isResumeRequest) {
                log.warn(
                        "Workflow {} is currently PAUSED on instance {}. Rejecting duplicate execution request without resume.",
                        workflowId,
                        coordination.getInstanceId()
                );
                return ExecutionLeaseAcquisitionResult.paused(coordination);
            }
            log.info(
                    "Workflow {} reclaiming lease from PAUSED on instance {} for resumed execution on instance {} (newGen={}).",
                    workflowId,
                    coordination.getInstanceId(),
                    instanceId,
                    nextGeneration
            );
        }

        if (currentStatus == ExecutionCoordinationStatus.DISPATCHED ||
                currentStatus == ExecutionCoordinationStatus.RUNNING) {
            if (coordination.getLeaseExpiresAt() != null && now.isBefore(coordination.getLeaseExpiresAt())) {
                // If same instance is re-dispatching from DISPATCHED (e.g. consumer retry before running), allow with same generation
                if (currentStatus == ExecutionCoordinationStatus.DISPATCHED && instanceId.equals(coordination.getInstanceId())) {
                    log.info("Re-dispatching in-flight DISPATCHED workflow {} on same instance {}", workflowId, instanceId);
                    coordination.setUpdatedAt(now);
                    coordination.setLeaseExpiresAt(leaseExpiresAt);
                    if (payloadJson != null) {
                        coordination.setPayloadJson(payloadJson);
                    }
                    WorkflowExecutionCoordination updated = repository.save(coordination);
                    return ExecutionLeaseAcquisitionResult.acquired(updated);
                }

                log.warn(
                        "Workflow {} is actively executing on instance {} (status={}, gen={}, leaseExpiresAt={}). Rejecting duplicate execution.",
                        workflowId,
                        coordination.getInstanceId(),
                        currentStatus,
                        coordination.getGeneration(),
                        coordination.getLeaseExpiresAt()
                );
                return ExecutionLeaseAcquisitionResult.duplicateActive(coordination);
            }

            log.warn(
                    "Workflow {} execution lease expired on instance {} (status={}, gen={}, leaseExpiresAt={}). Reclaiming lease on instance {} (newGen={}).",
                    workflowId,
                    coordination.getInstanceId(),
                    currentStatus,
                    coordination.getGeneration(),
                    coordination.getLeaseExpiresAt(),
                    instanceId,
                    nextGeneration
            );
        } else if (currentStatus == ExecutionCoordinationStatus.FAILED) {
            log.info(
                    "Workflow {} previously failed on instance {}. Reclaiming lease for re-execution/retry on instance {} (newGen={}).",
                    workflowId,
                    coordination.getInstanceId(),
                    instanceId,
                    nextGeneration
            );
            coordination.setRecoveryAttempts(0);
            coordination.setLastCompletedExecutionOrder(null);
        }

        coordination.setWorkflowName(workflowName);
        coordination.setStatus(ExecutionCoordinationStatus.DISPATCHED);
        coordination.setInstanceId(instanceId);
        coordination.setUpdatedAt(now);
        coordination.setLeaseExpiresAt(leaseExpiresAt);
        coordination.setGeneration(nextGeneration);
        if (payloadJson != null) {
            coordination.setPayloadJson(payloadJson);
        }

        WorkflowExecutionCoordination updated = repository.save(coordination);
        return ExecutionLeaseAcquisitionResult.acquired(updated);
    }

    @Transactional
    public void markRunning(Long workflowId, Long generation, String instanceId, long leaseDurationSeconds) {
        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plusSeconds(leaseDurationSeconds);

        int updated = repository.markRunningFenced(workflowId, generation, instanceId, now, leaseExpiresAt);
        if (updated == 0) {
            log.error("Failed to mark workflow {} as RUNNING: worker is fenced (gen={}, instance={})",
                    workflowId, generation, instanceId);
            throw new StaleWorkerExecutionException(
                    "Worker fenced when marking RUNNING for workflow " + workflowId + " (gen=" + generation + ")"
            );
        }
        log.info("Marked workflow {} as RUNNING (gen={}) on instance {}", workflowId, generation, instanceId);
    }

    @Transactional
    public void markRunning(Long workflowId, String instanceId, long leaseDurationSeconds) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(c ->
                markRunning(workflowId, c.getGeneration(), instanceId, leaseDurationSeconds)
        );
    }

    @Transactional
    public boolean heartbeat(Long workflowId, Long generation, String instanceId, long leaseDurationSeconds) {
        Instant now = Instant.now();
        Instant newExpiry = now.plusSeconds(leaseDurationSeconds);

        int updated = repository.updateHeartbeat(workflowId, generation, instanceId, now, newExpiry);
        if (updated > 0) {
            log.debug("Heartbeat extended lease for workflow {} (gen={}) on instance {} to {}",
                    workflowId, generation, instanceId, newExpiry);
            return true;
        } else {
            log.warn("Heartbeat rejected for workflow {} (gen={}) on instance {}: lease stolen, fenced, or not RUNNING",
                    workflowId, generation, instanceId);
            return false;
        }
    }

    @Transactional
    public void markCompleted(Long workflowId, Long generation, String instanceId, boolean success) {
        ExecutionCoordinationStatus targetStatus = success
                ? ExecutionCoordinationStatus.COMPLETED
                : ExecutionCoordinationStatus.FAILED;

        int updated = repository.markCompletedFenced(
                workflowId,
                generation,
                instanceId,
                targetStatus,
                Instant.now()
        );

        if (updated == 0) {
            log.warn("Fenced worker (gen={}, instance={}) attempted to mark workflow {} as {}",
                    generation, instanceId, workflowId, targetStatus);
            throw new StaleWorkerExecutionException(
                    "Fenced worker cannot mark completion for workflow " + workflowId
            );
        }
        log.info("Marked workflow {} as {} (gen={}) in coordination record", workflowId, targetStatus, generation);
    }

    @Transactional
    public void markCompleted(Long workflowId, boolean success) {
        repository.findById(workflowId).ifPresent(c -> {
            Instant now = Instant.now();
            c.setStatus(success ? ExecutionCoordinationStatus.COMPLETED : ExecutionCoordinationStatus.FAILED);
            c.setUpdatedAt(now);
            c.setLeaseExpiresAt(null);
            repository.save(c);
        });
    }

    @Transactional
    public void markPaused(Long workflowId, Long generation, String instanceId, Integer lastCompletedOrder) {
        int updated = repository.markPausedFenced(
                workflowId,
                generation,
                instanceId,
                lastCompletedOrder,
                Instant.now()
        );

        if (updated == 0) {
            log.warn("Fenced worker (gen={}, instance={}) attempted to pause workflow {}",
                    generation, instanceId, workflowId);
            throw new StaleWorkerExecutionException(
                    "Fenced worker cannot mark pause for workflow " + workflowId
            );
        }
        log.info("Marked workflow {} as PAUSED (checkpoint={}, gen={}) in coordination record",
                workflowId, lastCompletedOrder, generation);
    }

    @Transactional
    public void markPaused(Long workflowId, Integer lastCompletedOrder) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(coordination -> {
            Instant now = Instant.now();
            coordination.setStatus(ExecutionCoordinationStatus.PAUSED);
            if (lastCompletedOrder != null) {
                coordination.setLastCompletedExecutionOrder(lastCompletedOrder);
            }
            coordination.setUpdatedAt(now);
            coordination.setLeaseExpiresAt(null);
            repository.save(coordination);
            log.info("Marked workflow {} as PAUSED (checkpoint={}) in coordination record",
                    workflowId, lastCompletedOrder);
        });
    }

    @Transactional
    public void markCancelled(Long workflowId) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(coordination -> {
            if (coordination.getStatus() != ExecutionCoordinationStatus.COMPLETED) {
                Instant now = Instant.now();
                coordination.setStatus(ExecutionCoordinationStatus.CANCELLED);
                coordination.setUpdatedAt(now);
                coordination.setLeaseExpiresAt(null);
                repository.save(coordination);
                log.info("Marked workflow {} as CANCELLED in coordination record", workflowId);
            }
        });
    }

    @Transactional
    public void markResumed(Long workflowId) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(coordination -> {
            if (coordination.getStatus() == ExecutionCoordinationStatus.PAUSED) {
                Instant now = Instant.now();
                coordination.setStatus(ExecutionCoordinationStatus.DISPATCHED);
                coordination.setUpdatedAt(now);
                coordination.setLeaseExpiresAt(Instant.EPOCH);
                repository.save(coordination);
                log.info("Marked workflow {} resumable (PAUSED -> DISPATCHED) in coordination record (gen={})",
                        workflowId, coordination.getGeneration());
            }
        });
    }

    @Transactional
    public void releaseLeaseOnDispatchFailure(Long workflowId) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(coordination -> {
            if (coordination.getStatus() == ExecutionCoordinationStatus.DISPATCHED) {
                repository.delete(coordination);
                log.info("Released execution lease for workflow {} following dispatch failure", workflowId);
            }
        });
    }

    @Transactional(readOnly = true)
    public boolean isFenced(Long workflowId, Long generation, String instanceId) {
        if (generation == null || instanceId == null) {
            return false;
        }
        return repository.findById(workflowId)
                .map(c -> !generation.equals(c.getGeneration()) || !instanceId.equals(c.getInstanceId()))
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(Long workflowId) {
        return repository.findById(workflowId)
                .map(c -> c.getStatus() == ExecutionCoordinationStatus.CANCELLED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isPaused(Long workflowId) {
        return repository.findById(workflowId)
                .map(c -> c.getStatus() == ExecutionCoordinationStatus.PAUSED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<WorkflowExecutionCoordination> findAbandonedExecutions(Instant now) {
        return repository.findAbandonedExecutions(now);
    }

    @Transactional
    public RecoveryResult reclaimForRecovery(
            Long workflowId,
            String recoveryInstanceId,
            long leaseDurationSeconds,
            int maxAttempts
    ) {
        Optional<WorkflowExecutionCoordination> opt = repository.findByWorkflowIdForUpdate(workflowId);
        if (opt.isEmpty()) {
            return new RecoveryResult(RecoveryOutcome.INELIGIBLE, null);
        }

        WorkflowExecutionCoordination c = opt.get();
        Instant now = Instant.now();

        // Must still be abandoned
        if (c.getStatus() != ExecutionCoordinationStatus.DISPATCHED &&
                c.getStatus() != ExecutionCoordinationStatus.RUNNING) {
            return new RecoveryResult(RecoveryOutcome.INELIGIBLE, c);
        }
        if (c.getLeaseExpiresAt() != null && now.isBefore(c.getLeaseExpiresAt())) {
            return new RecoveryResult(RecoveryOutcome.INELIGIBLE, c);
        }

        int attempts = c.getRecoveryAttempts() != null ? c.getRecoveryAttempts() : 0;
        if (attempts >= maxAttempts) {
            log.error("Workflow {} exceeded maximum recovery attempts ({}/{}). Marking FAILED.",
                    workflowId, attempts, maxAttempts);
            c.setStatus(ExecutionCoordinationStatus.FAILED);
            c.setUpdatedAt(now);
            c.setLeaseExpiresAt(null);
            WorkflowExecutionCoordination saved = repository.save(c);
            return new RecoveryResult(RecoveryOutcome.FAILED_EXCEEDED_ATTEMPTS, saved);
        }

        long nextGen = (c.getGeneration() != null ? c.getGeneration() : 1L) + 1L;
        c.setGeneration(nextGen);
        c.setRecoveryAttempts(attempts + 1);
        c.setInstanceId(recoveryInstanceId);
        c.setStatus(ExecutionCoordinationStatus.DISPATCHED);
        c.setUpdatedAt(now);
        c.setLeaseExpiresAt(now.plusSeconds(leaseDurationSeconds));

        WorkflowExecutionCoordination saved = repository.save(c);
        log.warn("Recovered abandoned workflow {} on instance {} (gen={}, attempt={}/{})",
                workflowId, recoveryInstanceId, nextGen, attempts + 1, maxAttempts);
        return new RecoveryResult(RecoveryOutcome.RECLAIMED, saved);
    }
}
