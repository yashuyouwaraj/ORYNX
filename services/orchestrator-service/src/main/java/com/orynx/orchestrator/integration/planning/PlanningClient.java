package com.orynx.orchestrator.integration.planning;

import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
import com.orynx.orchestrator.ai.dto.AiWorkflowRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class PlanningClient {

    @Value("${planning.service.url}")
    private String planningServiceUrl;

    private final WebClient.Builder webClientBuilder;

    public AiWorkflowResponse generateWorkflowPlan(String goal) {

        AiWorkflowRequest request = new AiWorkflowRequest();
        request.setGoal(goal);

        return webClientBuilder
                .baseUrl(planningServiceUrl)
                .build()
                .post()
                .uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiWorkflowResponse.class)
                .block();
    }
}