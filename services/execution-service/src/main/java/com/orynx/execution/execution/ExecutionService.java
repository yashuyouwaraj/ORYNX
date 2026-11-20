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
        log.info(
                "Submitting workflow {} (id={}) to execution dispatcher",
                event.getWorkflowName(),
                event.getWorkflowId()
        );
        dispatcher.dispatch(event);
    }
}
