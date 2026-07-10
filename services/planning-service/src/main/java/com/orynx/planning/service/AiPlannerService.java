package com.orynx.planning.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.planning.dto.AiWorkflowResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
@Slf4j
public class AiPlannerService {

    @Value("${nvidia.api.url}")
    private String apiUrl;

    @Value("${nvidia.api.key}")
    private String apiKey;

    private final ObjectMapper objectMapper =
            new ObjectMapper();
    private final WebClient.Builder webclientBuilder;

    public AiWorkflowResponse
    generateWorkflowPlan(
            String goal
    ) {

        try {

            WebClient client =
                    webclientBuilder
                            .baseUrl(apiUrl)
                            .defaultHeader(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + apiKey
                            )
                            .codecs(configurer ->
                                    configurer.defaultCodecs()
                                            .maxInMemorySize(
                                                    10 * 1024 * 1024
                                            )
                            )
                            .build();

            String prompt = """
                Generate a concise orchestration workflow for this goal:
                %s

                Return only valid JSON, with no Markdown or explanation, in exactly this shape:
                {"workflowName":"short descriptive name","tasks":["task 1","task 2","task 3","task 4","task 5"]}

                The tasks must be five distinct, actionable execution steps.
                """.formatted(goal);

            Map<String, Object> requestBody =
                    Map.of(
                            "model",
                            "meta/llama-3.1-8b-instruct",

                            "messages",
                            List.of(
                                    Map.of(
                                            "role",
                                            "user",
                                            "content",
                                            prompt
                                    )
                            ),

                            "temperature",
                            0.2,

                            "max_tokens",
                            150
                    );

            String response =
                    client.post()
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

            JsonNode root =
                    objectMapper.readTree(response);

            String content =
                    root.path("choices")
                            .get(0)
                            .path("message")
                            .path("content")
                            .asText();

            log.info(
                    "AI Response: {}",
                    content
            );

            return parseWorkflowPlan(content);

        } catch (Exception e) {

            log.error(
                    "AI Planner Error",
                    e
            );

            throw new RuntimeException(
                    e.getMessage()
            );
        }
    }

    private AiWorkflowResponse parseWorkflowPlan(String content) throws Exception {
        String json = content.trim()
                .replaceFirst("(?is)^```(?:json)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "");
        JsonNode plan = objectMapper.readTree(json);

        String workflowName = plan.path("workflowName").asText().trim();
        List<String> tasks = new ArrayList<>();
        for (JsonNode task : plan.path("tasks")) {
            String taskName = task.asText().trim();
            if (!taskName.isBlank()) {
                tasks.add(taskName);
            }
        }

        if (workflowName.isBlank() || tasks.size() != 5) {
            throw new IllegalArgumentException("AI response must contain a workflow name and exactly five tasks");
        }

        return AiWorkflowResponse.builder()
                .workflowName(workflowName)
                .tasks(tasks)
                .build();
    }

}
