package com.orynx.execution.coordination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionCoordinationService {

    private final WorkflowExecutionCoordinationRepository repository;

    @Transactional
    public ExecutionLeaseAcquisitionResult tryAcquireLease(
            Long workflowId,
            String workflowName,
            String instanceId,
            long leaseDurationSeconds
    ) {
        try {
            return doAcquireLease(workflowId, workflowName, instanceId, leaseDurationSeconds);
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
            long leaseDurationSeconds
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
                    .build();

            WorkflowExecutionCoordination saved = repository.save(coordination);
            log.info("Acquired initial execution lease for workflow {} on instance {}", workflowId, instanceId);
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

        if (currentStatus == ExecutionCoordinationStatus.PAUSED) {
            log.warn(
                    "Workflow {} is currently PAUSED on instance {}. Rejecting duplicate execution request without resume.",
                    workflowId,
                    coordination.getInstanceId()
            );
            return ExecutionLeaseAcquisitionResult.paused(coordination);
        }

        if (currentStatus == ExecutionCoordinationStatus.DISPATCHED ||
                currentStatus == ExecutionCoordinationStatus.RUNNING) {
            if (coordination.getLeaseExpiresAt() != null && now.isBefore(coordination.getLeaseExpiresAt())) {
                log.warn(
                        "Workflow {} is actively executing on instance {} (status={}, leaseExpiresAt={}). Rejecting duplicate execution.",
                        workflowId,
                        coordination.getInstanceId(),
                        currentStatus,
                        coordination.getLeaseExpiresAt()
                );
                return ExecutionLeaseAcquisitionResult.duplicateActive(coordination);
            }

            log.warn(
                    "Workflow {} execution lease expired on instance {} (status={}, leaseExpiresAt={}). Reclaiming lease on instance {}.",
                    workflowId,
                    coordination.getInstanceId(),
                    currentStatus,
                    coordination.getLeaseExpiresAt(),
                    instanceId
            );
        } else if (currentStatus == ExecutionCoordinationStatus.FAILED) {
            log.info(
                    "Workflow {} previously failed on instance {}. Reclaiming lease for re-execution/retry on instance {}.",
                    workflowId,
                    coordination.getInstanceId(),
                    instanceId
            );
        }

        coordination.setWorkflowName(workflowName);
        coordination.setStatus(ExecutionCoordinationStatus.DISPATCHED);
        coordination.setInstanceId(instanceId);
        coordination.setUpdatedAt(now);
        coordination.setLeaseExpiresAt(leaseExpiresAt);

        WorkflowExecutionCoordination updated = repository.save(coordination);
        return ExecutionLeaseAcquisitionResult.acquired(updated);
    }

    @Transactional
    public void markRunning(Long workflowId, String instanceId, long leaseDurationSeconds) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(coordination -> {
            Instant now = Instant.now();
            coordination.setStatus(ExecutionCoordinationStatus.RUNNING);
            coordination.setInstanceId(instanceId);
            coordination.setUpdatedAt(now);
            coordination.setLeaseExpiresAt(now.plusSeconds(leaseDurationSeconds));
            repository.save(coordination);
            log.info("Marked workflow {} as RUNNING on instance {}", workflowId, instanceId);
        });
    }

    @Transactional
    public void markCompleted(Long workflowId, boolean success) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(coordination -> {
            Instant now = Instant.now();
            coordination.setStatus(success ? ExecutionCoordinationStatus.COMPLETED : ExecutionCoordinationStatus.FAILED);
            coordination.setUpdatedAt(now);
            coordination.setLeaseExpiresAt(null);
            repository.save(coordination);
            log.info("Marked workflow {} as {} in coordination record", workflowId, coordination.getStatus());
        });
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
            log.info(
                    "Marked workflow {} as PAUSED (checkpoint={}) in coordination record",
                    workflowId,
                    lastCompletedOrder
            );
        });
    }

    @Transactional
    public void markCancelled(Long workflowId) {
        repository.findByWorkflowIdForUpdate(workflowId).ifPresent(coordination -> {
            Instant now = Instant.now();
            coordination.setStatus(ExecutionCoordinationStatus.CANCELLED);
            coordination.setUpdatedAt(now);
            coordination.setLeaseExpiresAt(null);
            repository.save(coordination);
            log.info("Marked workflow {} as CANCELLED in coordination record", workflowId);
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
}
