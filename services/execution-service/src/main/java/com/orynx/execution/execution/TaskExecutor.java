package com.orynx.execution.execution;

import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.BooleanSupplier;

@Component
@Slf4j
public class TaskExecutor {
    private static final long DEFAULT_EXECUTION_DELAY_MS = 1500L;

    private final BooleanSupplier attemptOutcomeSupplier;
    private final long executionDelayMs;

    @Autowired
    public TaskExecutor() {
        this(() -> Math.random() > 0.4, DEFAULT_EXECUTION_DELAY_MS);
    }

    TaskExecutor(BooleanSupplier attemptOutcomeSupplier, long executionDelayMs) {
        this.attemptOutcomeSupplier = Objects.requireNonNull(
                attemptOutcomeSupplier,
                "attemptOutcomeSupplier must not be null"
        );
        this.executionDelayMs = Math.max(0L, executionDelayMs);
    }

    public TaskExecutionResult execute(TaskExecutionRequest task) {
        int maxRetries = (task.getMaxRetries() == null || task.getMaxRetries() < 0)
                ? 0
                : task.getMaxRetries();
        int maxAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info(
                    "Executing {} (Attempt {}/{})",
                    task.getName(),
                    attempt,
                    maxAttempts
            );

            if (executionDelayMs > 0L) {
                try {
                    Thread.sleep(executionDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("{} execution interrupted on attempt {}.", task.getName(), attempt, e);
                    return TaskExecutionResult.builder()
                            .taskName(task.getName())
                            .success(false)
                            .attempts(attempt)
                            .build();
                }
            }

            boolean success = attemptOutcomeSupplier.getAsBoolean();

            if (success) {
                log.info("{} completed.", task.getName());
                return TaskExecutionResult.builder()
                        .taskName(task.getName())
                        .success(true)
                        .attempts(attempt)
                        .build();
            }

            if (attempt < maxAttempts) {
                log.warn(
                        "{} failed. Retrying...",
                        task.getName()
                );
            } else {
                log.error(
                        "{} failed after {} attempt(s).",
                        task.getName(),
                        attempt
                );
            }
        }
        return TaskExecutionResult.builder()
                .taskName(task.getName())
                .success(false)
                .attempts(maxAttempts)
                .build();
    }
}
