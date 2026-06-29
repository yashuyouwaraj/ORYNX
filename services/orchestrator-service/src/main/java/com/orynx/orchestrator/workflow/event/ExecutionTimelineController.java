package com.orynx.orchestrator.workflow.event;

import com.orynx.orchestrator.workflow.event.dto.ExecutionTimelineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/timeline")
@RequiredArgsConstructor
public class ExecutionTimelineController {

    private final ExecutionTimelineService executionTimelineService;

    @GetMapping("/{workflowId}")
    public List<ExecutionTimelineResponse>
    getTimeline(@PathVariable Long workflowId) {
        return executionTimelineService
                .getWorkflowTimeline(
                        workflowId
                );
    }
}