package com.orynx.orchestrator.workflow.event;

import com.orynx.orchestrator.workflow.event.dto.ExecutionTimelineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/timeline")
@RequiredArgsConstructor
public class ExecutionTimelineController {

    private final ExecutionTimelineService executionTimelineService;

    @GetMapping("/{workflowId}")
    public Page<ExecutionTimelineResponse> getTimeline(
            @PathVariable Long workflowId,
            Pageable pageable
    ) {
        return executionTimelineService.getWorkflowTimeline(workflowId, pageable);
    }
}