package com.orynx.orchestrator.integration.planning;

import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
import com.orynx.orchestrator.ai.dto.AiWorkflowRequest;
import com.orynx.orchestrator.integration.planning.config.PlanningServiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class PlanningClient {
    private final PlanningServiceProperties properties;

    private final WebClient.Builder webClientBuilder;

    public AiWorkflowResponse generateWorkflowPlan(String goal) {

        AiWorkflowRequest request = new AiWorkflowRequest();
        request.setGoal(goal);


        return webClientBuilder
                .baseUrl(properties.getUrl())
                .build()
                .post()
                .uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        ClientResponse::createException
                )
                .bodyToMono(AiWorkflowResponse.class)
                .block();
    }
}