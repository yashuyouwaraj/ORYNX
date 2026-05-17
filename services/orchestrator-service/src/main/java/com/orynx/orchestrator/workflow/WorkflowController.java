package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowService workflowService;

    @PostMapping
    public Workflow createWorkflow(@Valid @RequestBody CreateWorkflowRequest request){
        return workflowService.createWorkflow(request);
    }
}
