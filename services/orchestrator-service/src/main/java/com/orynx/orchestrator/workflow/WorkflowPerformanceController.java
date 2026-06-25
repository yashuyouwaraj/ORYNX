package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceResponse;
import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/performance")
@RequiredArgsConstructor
public class WorkflowPerformanceController {
    private final WorkflowPerformanceService workflowPerformanceService;

    @GetMapping
    public List<WorkflowPerformanceResponse> getPerformance(){
        return workflowPerformanceService.getWorkflowPerformance();
    }

    @GetMapping("/summary")
    public WorkflowPerformanceSummaryResponse getPerformanceSummary(){
        return workflowPerformanceService.getPerformanceSummary();
    }
}


