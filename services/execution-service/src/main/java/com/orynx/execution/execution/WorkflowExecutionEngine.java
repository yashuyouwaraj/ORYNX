package com.orynx.execution.execution;

import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionEngine {
    private final TaskExecutor taskExecutor;
    public void execute(WorkflowExecutionRequestEvent event){
        log.info("========================================");
        log.info("WorkflowExecutionEngine Started");
        log.info("Workflow : {}", event.getWorkflowName());

        for(TaskExecutionRequest task: event.getTasks()){
            TaskExecutionResult result = taskExecutor.execute(task);

            if(!result.isSuccess()){
                log.error("Workflow {} failed because task {} failed",
                        event.getWorkflowName(),
                        task.getName()
                        );
                return;
            }
        }
        log.info("Workflow {} completed successfully", event.getWorkflowName());
    }
}
