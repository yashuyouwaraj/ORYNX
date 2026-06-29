package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowAnalyticsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class WorkflowAnalyticsController {
    private final WorkflowAnalyticsService workflowAnalyticsService;

    @GetMapping
    public WorkflowAnalyticsResponse getAnalytics(){
        return workflowAnalyticsService.getAnalytics();
    }
}
