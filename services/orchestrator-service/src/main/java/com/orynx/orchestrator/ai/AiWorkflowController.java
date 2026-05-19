package com.orynx.orchestrator.ai;

import com.orynx.orchestrator.ai.dto.AiWorkflowRequest;
import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
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
    private final AiPlannerService aiPlannerService;

    @PostMapping("/plan")
    public AiWorkflowResponse planWorkflow(@Valid @RequestBody AiWorkflowRequest request){
        return aiPlannerService.generateWorkflowPlan(request.getGoal());
    }
}
