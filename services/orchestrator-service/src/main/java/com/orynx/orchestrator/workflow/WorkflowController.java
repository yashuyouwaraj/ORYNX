package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.dto.DashboardSummaryResponse;
import com.orynx.orchestrator.workflow.execution.dto.WorkflowAnalyticsResponse;
import com.orynx.orchestrator.workflow.execution.dto.WorkflowExecutionResponse;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowService workflowService;

    @PostMapping
    public Workflow createWorkflow(@Valid @RequestBody CreateWorkflowRequest request){
        return workflowService.createWorkflow(request);
    }

    @PatchMapping("/{id}/start")
    public Workflow startWorkflow(@PathVariable Long id){
        return workflowService.startWorkflow(id);
    }

    @GetMapping
    public Page<Workflow> getAllWorkflows(Pageable pageable){
        return workflowService.getAllWorkflows(pageable);
    }

    @GetMapping("/{id}")
    public Workflow getWorkflow(@PathVariable Long id){
        return workflowService.getWorkflow(id);
    }

    @GetMapping("/status/{status}")
    public List<Workflow> getWorkflowsByStatus(@PathVariable WorkflowStatus status){
        return workflowService.getWorkflowsByStatus(status);
    }

    @GetMapping("/{id}/tasks")
    public List<WorkflowTask> getWorkflowTasks(@PathVariable Long id){
        return workflowService.getWorkflowTasks(id);
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummaryResponse getDashboardSummary(){
        return workflowService.getDashboardSummary();
    }

    @GetMapping("/{id}/executions")
    public List<WorkflowExecutionResponse> getWorkflowExecutionHistory(@PathVariable Long id){
        return workflowService.getWorkflowExecutionHistory(id);
    }

    @GetMapping("/{id}/analytics")
    public WorkflowAnalyticsResponse getWorkflowAnalytics(
            @PathVariable Long id
    ){
        return workflowService.getWorkflowAnalytics(id);
    }

    @PatchMapping("/{id}/cancel")
    public Workflow cancelWorkflow(@PathVariable Long id){
        return workflowService.cancelWorkflow(id);
    }
}
