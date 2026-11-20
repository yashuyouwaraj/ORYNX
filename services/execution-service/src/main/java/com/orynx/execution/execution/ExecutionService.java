package com.orynx.execution.execution;

import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionService {
    private final WorkflowExecutionDispatcher dispatcher;

    public void executeWorkflow(WorkflowExecutionRequestEvent event) {
        executeWorkflow(event, 1L);
    }

    public void executeWorkflow(WorkflowExecutionRequestEvent event, Long generation) {
        log.info(
                "Submitting workflow {} (id={}, gen={}) to execution dispatcher",
                event.getWorkflowName(),
                event.getWorkflowId(),
                generation
        );
        dispatcher.dispatch(event, generation);
    }
}
