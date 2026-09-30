package com.orynx.orchestrator.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowScheduler {
    private final WorkflowRepository workflowRepository;
    private final WorkflowService workflowService;

    @Scheduled(fixedDelay = 10000)
    public void executeScheduledWorkflows() {

        LocalDateTime now = LocalDateTime.now();

        List<Workflow> dueWorkflows =
                workflowRepository.findDueScheduledWorkflows(now);

        if (dueWorkflows.isEmpty()) {
            return;
        }

        log.info(
                "Found {} scheduled workflow(s) ready for execution",
                dueWorkflows.size()
        );

        for (Workflow workflow : dueWorkflows) {

            try {

                int claimed =
                        workflowRepository.claimScheduledWorkflow(
                                workflow.getId()
                        );

                if (claimed == 0) {
                    log.info(
                            "Workflow {} was already claimed. Skipping.",
                            workflow.getId()
                    );
                    continue;
                }

                log.info(
                        "Claimed scheduled workflow: {} (id={})",
                        workflow.getName(),
                        workflow.getId()
                );

                workflowService.startWorkflow(workflow.getId());

            } catch (Exception e) {

                log.error(
                        "Failed to start scheduled workflow: {} (id={})",
                        workflow.getName(),
                        workflow.getId(),
                        e
                );

                int released =
                        workflowRepository.releaseScheduledWorkflow(
                                workflow.getId()
                        );

                if (released > 0) {
                    log.info(
                            "Released scheduler claim for workflow {}",
                            workflow.getId()
                    );
                } else {
                    log.info(
                            "Workflow {} was not released because its state is no longer CREATED",
                            workflow.getId()
                    );
                }
            }
        }
    }
}
