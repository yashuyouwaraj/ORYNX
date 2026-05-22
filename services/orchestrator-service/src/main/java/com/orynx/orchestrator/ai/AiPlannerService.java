package com.orynx.orchestrator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
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

    public AiWorkflowResponse
    generateWorkflowPlan(
            String goal
    ) {

        try {

            WebClient client =
                    WebClient.builder()
                            .baseUrl(apiUrl)
                            .defaultHeader(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + apiKey
                            )
                            .defaultHeader(
                                    HttpHeaders.ACCEPT,
                                    "application/json"
                            )
                            .codecs(configurer ->
                                    configurer.defaultCodecs()
                                            .maxInMemorySize(
                                                    10 * 1024 * 1024
                                            )
                            )
                            .build();

            String prompt = """
                Generate a concise orchestration workflow.

                Goal:
                %s

                Return ONLY:
                1. Workflow Name
                2. 5 execution tasks

                Keep response short.
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

            List<String> tasks =
                    extractTasks(content);

            return AiWorkflowResponse.builder()
                    .workflowName(goal)
                    .tasks(tasks)
                    .build();

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

    private List<String> extractTasks(
            String content
    ) {

        List<String> tasks =
                new ArrayList<>();

        String[] lines =
                content.split("\n");

        for (String line : lines) {

            if (
                    line.matches(
                            "^\\d+\\..*"
                    )
            ) {

                String cleanedTask = line
                        .replaceAll("^\\d+\\.\\s*", "")
                        .replaceAll("\\*\\*", "")
                        .replaceAll("Task\\s*\\d+\\s*:\\s*", "")
                        .trim();

                tasks.add(cleanedTask);
            }
        }

        return tasks;
    }
}