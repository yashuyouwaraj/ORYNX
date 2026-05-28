package com.orynx.orchestrator.workflow.event;

import com.orynx.orchestrator.workflow.event.dto.ExecutionTimelineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutionTimelineService {
    private final ExecutionEventRepository executionEventRepository;

    public List<ExecutionTimelineResponse> getWorkflowTimeline(Long workflowId){
        return executionEventRepository.findByWorkflowIdOrderByTimestampDesc(workflowId)
                .stream()
                .map(event->
                        ExecutionTimelineResponse.builder()
                                .taskName(event.getTaskName())
                                .status(event.getStatus())
                                .timestamp(event.getTimestamp())
                                .build())
                .toList();
    }
}
