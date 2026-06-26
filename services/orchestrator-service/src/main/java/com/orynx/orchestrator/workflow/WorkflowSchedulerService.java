package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowSchedulerService {
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionEngine workflowExecutionEngine;

    @Scheduled(fixedDelay = 10000)
    public void checkScheduledWorkflows() {
        LocalDateTime now = LocalDateTime.now();
        var workflows = workflowRepository.findDueScheduledWorkflows(now);

        log.info("Scheduler tick at {} — found {} due workflow(s)", now, workflows.size());

        for (Workflow workflow : workflows) {
            log.info(
                    "Dispatching scheduled workflow id={} name={} scheduledAt={}",
                    workflow.getId(),
                    workflow.getName(),
                    workflow.getScheduledAt()
            );

            workflow.setScheduled(false);
            workflowRepository.saveAndFlush(workflow);

            workflowExecutionEngine.executeWorkflowAsync(workflow.getId());
        }
    }
}
