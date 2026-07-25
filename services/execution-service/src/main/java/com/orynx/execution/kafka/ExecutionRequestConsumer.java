package com.orynx.execution.kafka;

import com.orynx.execution.execution.ExecutionService;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutionRequestConsumer {
    private final ExecutionService executionService;

    @KafkaListener(topics = "workflow-execution",groupId = "execution-service")
    public void consume(WorkflowExecutionRequestEvent event){
        log.info("========================================");
        log.info("Received Workflow Execution Request");
        log.info("Workflow Id   : {}", event.getWorkflowId());
        log.info("Workflow Name : {}", event.getWorkflowName());

        if(event.getTasks()!=null){
            log.info("Tasks:");

            event.getTasks().forEach(task->
                            log.info(
                                    "   {}. {} (Retries={})",
                                    task.getExecutionOrder(),
                                    task.getName(),
                                    task.getMaxRetries()
                            )
                    );
        }

        executionService.executeWorkflow(event);
    }
}
