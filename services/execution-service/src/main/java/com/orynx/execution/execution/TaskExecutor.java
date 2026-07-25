package com.orynx.execution.execution;

import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TaskExecutor {
    public TaskExecutionResult execute(TaskExecutionRequest task){
        int maxAttempts = task.getMaxRetries()+1;
        for (int attempt = 1; attempt <=maxAttempts ; attempt++) {
            log.info(
                    "Executing {} (Attempt {}/{})",
                    task.getName(),
                    attempt,
                    maxAttempts
            );

            try {
                Thread.sleep(1500);
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
            }

            boolean success = Math.random() > 0.4;

            if(success || attempt==maxAttempts){
                log.info("{} completed.", task.getName());
                return TaskExecutionResult.builder()
                        .taskName(task.getName())
                        .success(true)
                        .attempts(attempt)
                        .build();
            }

            log.warn(
                    "{} failed. Retrying...",
                    task.getName()
            );
        }
        return TaskExecutionResult.builder()
                .taskName(task.getName())
                .success(false)
                .attempts(maxAttempts)
                .build();
    }
}
