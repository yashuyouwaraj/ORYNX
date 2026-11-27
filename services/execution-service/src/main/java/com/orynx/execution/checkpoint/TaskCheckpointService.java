package com.orynx.execution.checkpoint;

import com.orynx.execution.coordination.StaleWorkerExecutionException;
import com.orynx.execution.observability.ExecutionMetrics;
import com.orynx.execution.observability.ExecutionObservabilitySanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Durable, transactional, generation-fenced task checkpoint service.
 * Guarantees:
 * - A stale execution generation can never overwrite a newer generation's task checkpoint.
 * - A COMPLETED checkpoint is never regressed or overwritten by stale/duplicate attempts.
 * - First-insert races on uk_wf_task_checkpoint (workflow_id, task_key) are resolved safely
 *   via bounded REQUIRES_NEW retry followed by PESSIMISTIC_WRITE row locking and generation check.
 * - Crash recovery reconstructs completed task outputs and skips already-completed tasks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskCheckpointService {

    public static final int MAX_INSERT_RACE_ATTEMPTS = 3;
    public static final int MAX_CHECKPOINT_OUTPUT_BYTES = 32_768;

    private final WorkflowTaskCheckpointRepository repository;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    @Autowired(required = false)
    private ExecutionMetrics executionMetrics;

    @Transactional(readOnly = true)
    public Map<String, WorkflowTaskCheckpoint> loadCheckpointsByTaskKey(Long workflowId) {
        Map<String, WorkflowTaskCheckpoint> map = new LinkedHashMap<>();
        if (workflowId == null) {
            return map;
        }
        List<WorkflowTaskCheckpoint> rows = repository.findByWorkflowId(workflowId);
        if (rows != null) {
            for (WorkflowTaskCheckpoint row : rows) {
                if (row.getTaskKey() != null) {
                    map.put(row.getTaskKey(), row);
                }
            }
        }
        return map;
    }

    @Transactional
    public void resetCheckpointsForFreshRun(Long workflowId, Long newGeneration) {
        if (workflowId == null) {
            return;
        }
        List<WorkflowTaskCheckpoint> existing = repository.findByWorkflowId(workflowId);
        if (existing == null || existing.isEmpty()) {
            return;
        }
        long gen = newGeneration != null ? newGeneration : 1L;
        for (WorkflowTaskCheckpoint cp : existing) {
            if (cp.getExecutionGeneration() != null && cp.getExecutionGeneration() > gen) {
                throw new StaleWorkerExecutionException(
                        "Cannot reset checkpoints for workflow " + workflowId + " with stale generation " + gen
                );
            }
        }
        repository.deleteByWorkflowId(workflowId);
    }

    public boolean markTaskRunning(
            Long workflowId,
            String taskKey,
            String taskName,
            String taskType,
            Integer executionOrder,
            String dependsOnJson,
            Long generation,
            String workerId,
            int attempt
    ) {
        long startNs = System.nanoTime();
        try {
            return executeWithInsertRaceRetry(workflowId, taskKey, () ->
                    doMarkTaskRunning(workflowId, taskKey, taskName, taskType, executionOrder,
                            dependsOnJson, generation, workerId, attempt));
        } finally {
            recordLatency(startNs);
        }
    }

    private boolean doMarkTaskRunning(
            Long workflowId,
            String taskKey,
            String taskName,
            String taskType,
            Integer executionOrder,
            String dependsOnJson,
            Long generation,
            String workerId,
            int attempt
    ) {
        long effectiveGen = generation != null ? generation : 1L;
        Instant now = Instant.now();
        Optional<WorkflowTaskCheckpoint> existingOpt =
                repository.findByWorkflowIdAndTaskKeyForUpdate(workflowId, taskKey);

        if (existingOpt.isPresent()) {
            WorkflowTaskCheckpoint existing = existingOpt.get();
            if (existing.getExecutionGeneration() != null && effectiveGen < existing.getExecutionGeneration()) {
                throw new StaleWorkerExecutionException(
                        "Stale generation " + effectiveGen + " attempted to start task " + taskKey
                                + " (active gen=" + existing.getExecutionGeneration() + ")"
                );
            }
            if (existing.getStatus() == TaskCheckpointStatus.COMPLETED) {
                log.info("Task checkpoint {}/{} already COMPLETED; skipping duplicate run", workflowId, taskKey);
                return false;
            }
            existing.setExecutionGeneration(effectiveGen);
            existing.setWorkerId(workerId);
            existing.setStatus(TaskCheckpointStatus.RUNNING);
            existing.setAttempt(Math.max(1, attempt));
            if (existing.getStartedAt() == null) {
                existing.setStartedAt(now);
            }
            existing.setUpdatedAt(now);
            repository.save(existing);
            return true;
        }

        WorkflowTaskCheckpoint created = WorkflowTaskCheckpoint.builder()
                .workflowId(workflowId)
                .taskKey(taskKey)
                .taskName(taskName)
                .taskType(taskType)
                .executionOrder(executionOrder)
                .dependsOnJson(dependsOnJson)
                .status(TaskCheckpointStatus.RUNNING)
                .executionGeneration(effectiveGen)
                .workerId(workerId)
                .attempt(Math.max(1, attempt))
                .startedAt(now)
                .updatedAt(now)
                .build();
        repository.saveAndFlush(created);
        return true;
    }

    public void markTaskTerminal(
            Long workflowId,
            String taskKey,
            String taskName,
            String taskType,
            Integer executionOrder,
            String dependsOnJson,
            TaskCheckpointStatus terminalStatus,
            Long generation,
            String workerId,
            int attempts,
            String outputJson,
            String errorMessage
    ) {
        long startNs = System.nanoTime();
        try {
            String boundedOutput = enforceOutputBound(outputJson);
            executeWithInsertRaceRetry(workflowId, taskKey, () -> {
                doMarkTaskTerminal(workflowId, taskKey, taskName, taskType, executionOrder,
                        dependsOnJson, terminalStatus, generation, workerId, attempts, boundedOutput, errorMessage);
                return Boolean.TRUE;
            });
        } finally {
            recordLatency(startNs);
        }
    }

    private void doMarkTaskTerminal(
            Long workflowId,
            String taskKey,
            String taskName,
            String taskType,
            Integer executionOrder,
            String dependsOnJson,
            TaskCheckpointStatus terminalStatus,
            Long generation,
            String workerId,
            int attempts,
            String outputJson,
            String errorMessage
    ) {
        long effectiveGen = generation != null ? generation : 1L;
        Instant now = Instant.now();
        String safeError = sanitizeError(errorMessage);
        Optional<WorkflowTaskCheckpoint> existingOpt =
                repository.findByWorkflowIdAndTaskKeyForUpdate(workflowId, taskKey);

        if (existingOpt.isPresent()) {
            WorkflowTaskCheckpoint existing = existingOpt.get();
            if (existing.getExecutionGeneration() != null && effectiveGen < existing.getExecutionGeneration()) {
                throw new StaleWorkerExecutionException(
                        "Stale generation " + effectiveGen + " attempted to mark task " + taskKey
                                + " as " + terminalStatus + " (active gen=" + existing.getExecutionGeneration() + ")"
                );
            }
            if (existing.getStatus() == TaskCheckpointStatus.COMPLETED
                    && terminalStatus != TaskCheckpointStatus.COMPLETED) {
                log.warn("Suppressing regression of COMPLETED checkpoint {}/{} to {}",
                        workflowId, taskKey, terminalStatus);
                return;
            }
            existing.setExecutionGeneration(effectiveGen);
            existing.setWorkerId(workerId);
            existing.setStatus(terminalStatus);
            existing.setAttempt(Math.max(existing.getAttempt() != null ? existing.getAttempt() : 0, attempts));
            if (outputJson != null) {
                existing.setOutputJson(outputJson);
            }
            if (safeError != null) {
                existing.setErrorMessage(safeError);
            }
            if (existing.getStartedAt() == null) {
                existing.setStartedAt(now);
            }
            existing.setCompletedAt(now);
            existing.setUpdatedAt(now);
            repository.save(existing);
            return;
        }

        WorkflowTaskCheckpoint created = WorkflowTaskCheckpoint.builder()
                .workflowId(workflowId)
                .taskKey(taskKey)
                .taskName(taskName)
                .taskType(taskType)
                .executionOrder(executionOrder)
                .dependsOnJson(dependsOnJson)
                .status(terminalStatus)
                .executionGeneration(effectiveGen)
                .workerId(workerId)
                .attempt(attempts)
                .outputJson(outputJson)
                .errorMessage(safeError)
                .startedAt(now)
                .completedAt(now)
                .updatedAt(now)
                .build();
        repository.saveAndFlush(created);
    }

    /**
     * Executes a checkpoint mutation in an isolated REQUIRES_NEW transaction (when a
     * PlatformTransactionManager is active) and retries up to MAX_INSERT_RACE_ATTEMPTS
     * on unique-constraint collision (DataIntegrityViolationException), ensuring the retry
     * re-acquires the row via PESSIMISTIC_WRITE and enforces fencing + terminal protection.
     */
    private <T> T executeWithInsertRaceRetry(Long workflowId, String taskKey, Supplier<T> action) {
        DataIntegrityViolationException lastEx = null;
        for (int attempt = 1; attempt <= MAX_INSERT_RACE_ATTEMPTS; attempt++) {
            try {
                if (transactionManager != null) {
                    TransactionTemplate tpl = new TransactionTemplate(transactionManager);
                    tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    return tpl.execute(status -> action.get());
                }
                return action.get();
            } catch (DataIntegrityViolationException dive) {
                lastEx = dive;
                log.warn("Concurrent first-insert race on checkpoint {}/{} (attempt {}/{}); retrying with row lock",
                        workflowId, taskKey, attempt, MAX_INSERT_RACE_ATTEMPTS);
            }
        }
        throw lastEx;
    }

    private String enforceOutputBound(String outputJson) {
        if (outputJson == null) {
            return null;
        }
        if (outputJson.getBytes(StandardCharsets.UTF_8).length > MAX_CHECKPOINT_OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "Checkpoint output exceeds maximum size of " + MAX_CHECKPOINT_OUTPUT_BYTES + " bytes"
            );
        }
        return outputJson;
    }

    private String sanitizeError(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String redacted = ExecutionObservabilitySanitizer.redactSensitiveText(raw);
        return redacted.length() > 512 ? redacted.substring(0, 512) : redacted;
    }

    private void recordLatency(long startNs) {
        if (executionMetrics != null) {
            executionMetrics.recordCheckpointDurationNanos(System.nanoTime() - startNs);
        }
    }
}
