package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowPerformanceService {
    private final WorkflowRepository workflowRepository;

    public List<WorkflowPerformanceResponse> getWorkflowPerformance(){
        return workflowRepository.findAll()
                .stream()
                .filter(workflow -> workflow.getStartedAt() !=null && workflow.getCompletedAt()!=null)
                .map(workflow -> WorkflowPerformanceResponse.builder()
                        .workflowName(workflow.getName())
                        .durationMs(workflow.getCompletedAt()-workflow.getStartedAt())
                        .build()
                )
                .sorted(Comparator.comparing(WorkflowPerformanceResponse::getDurationMs))
                .toList();
    }
}
