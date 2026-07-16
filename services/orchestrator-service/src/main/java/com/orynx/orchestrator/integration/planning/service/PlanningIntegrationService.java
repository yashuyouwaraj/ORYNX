package com.orynx.orchestrator.integration.planning.service;

import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
import com.orynx.orchestrator.integration.planning.PlanningClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanningIntegrationService {
    private final PlanningClient planningClient;

    @Retry(name="planningService")
    @CircuitBreaker(name="planningService",fallbackMethod="fallbackWorkflowPlan")
    public AiWorkflowResponse generateWorkflowPlan(String goal){
        log.info("Sending request to Planning Service...");

        return planningClient.generateWorkflowPlan(goal);
    }

    public AiWorkflowResponse fallbackWorkflowPlan(String goal, Exception ex){
        log.warn(
                "Planning Service unavailable after retries: {}. Using emergency workflow.",
                ex.getMessage()
        );

        return AiWorkflowResponse.builder()
                .workflowName("Emergency Workflow Plan")
                .tasks(
                        List.of(
                                "Collect System Metrics",
                                "Analyze Available Logs",
                                "Generate Incident Report",
                                "Notify Platform Administrator",
                                "Close Emergency Workflow"
                        )
                )
                .build();
    }
}
