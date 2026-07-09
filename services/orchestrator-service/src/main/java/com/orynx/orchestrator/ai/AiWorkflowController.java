package com.orynx.orchestrator.ai;

import com.orynx.orchestrator.ai.dto.AiWorkflowRequest;
import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
import com.orynx.orchestrator.integration.planning.PlanningClient;
import com.orynx.orchestrator.workflow.Workflow;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiWorkflowController {
    private final PlanningClient planningClient;
    private final AiWorkflowService aiWorkflowService;

    @PostMapping("/plan")
    public AiWorkflowResponse planWorkflow(@Valid @RequestBody AiWorkflowRequest request){
        return planningClient.generateWorkflowPlan(request.getGoal());
    }

    @PostMapping("/workflows")
    public Workflow createAiWorkflow(@Valid @RequestBody AiWorkflowRequest request){
        return aiWorkflowService.createdAiWorkflow(request);
    }
}
