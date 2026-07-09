package com.orynx.planning.controller;

import com.orynx.planning.dto.AiWorkflowResponse;
import com.orynx.planning.dto.PlanningRequest;
import com.orynx.planning.service.AiPlannerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanningController {
    private final AiPlannerService aiPlannerService;

    @PostMapping
    public AiWorkflowResponse generatePlan(
            @Valid
            @RequestBody PlanningRequest request
            ){
        return aiPlannerService.generateWorkflowPlan(request.getGoal());
    }
}
