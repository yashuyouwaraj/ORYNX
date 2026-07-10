package com.orynx.orchestrator.integration.planning.service;

import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
import com.orynx.orchestrator.integration.planning.PlanningClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanningIntegrationService {
    private final PlanningClient planningClient;

    @CircuitBreaker(name="planningService",fallbackMethod="fallbackWorkflowPlan")
    public AiWorkflowResponse generateWorkflowPlan(String goal){
        log.info("Calling Planning Service...");

        return planningClient.generateWorkflowPlan(goal);
    }

    public AiWorkflowResponse fallbackWorkflowPlan(String goal, Exception ex){
        log.warn("Planning Service unavailable. Using fallback workflow.");

        return AiWorkflowResponse.builder()
                .workflowName("Fallback Workflow")
                .tasks(
                        List.of(
                                "Collect Metrics",
                                "Analyze Logs",
                                "Generate Incident Report",
                                "Notify Administrator",
                                "Close Workflow"
                        )
                )
                .build();
    }
}
