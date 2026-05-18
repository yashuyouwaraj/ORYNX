package com.orynx.orchestrator.workflow.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutionEngine {
    private final WorkflowTaskRepository workflowTaskRepository;

    public void executeWorkflow(Long workflowId){
        List<WorkflowTask> tasks = workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(workflowId);

        if (tasks.isEmpty()) {
            log.warn("No tasks found for workflow: {}", workflowId);
            return;
        }

        for (WorkflowTask task: tasks){
            log.info("Executing task: {}",task.getName());

            task.setStatus(TaskStatus.RUNNING);

            workflowTaskRepository.save(task);

            try{
                Thread.sleep(2000);
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
            }

            task.setStatus(TaskStatus.COMPLETED);

            workflowTaskRepository.save(task);

            log.info("Completed task: {}",task.getName());
        }
    }
}
