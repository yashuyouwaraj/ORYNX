package com.orynx.execution.execution;

import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowExecutionEngine {
    public void execute(WorkflowExecutionRequestEvent event){
        log.info("========================================");
        log.info("WorkflowExecutionEngine Started");
        log.info("Workflow : {}", event.getWorkflowName());

        event.getTasks().forEach(task->{
            log.info(
                    "Executing Task {} : {}",
                    task.getExecutionOrder(),
                    task.getName()
            );
        });
        log.info("Workflow Execution Finished");


    }
}
