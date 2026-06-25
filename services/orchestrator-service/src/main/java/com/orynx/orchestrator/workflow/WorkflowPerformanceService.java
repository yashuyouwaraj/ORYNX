package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.workflow.dto.WorkflowHealthResponse;
import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceResponse;
import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceSummaryResponse;
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

    public WorkflowPerformanceSummaryResponse getPerformanceSummary(){
        var completed = workflowRepository.findAll()
                .stream()
                .filter(w-> w.getStartedAt() !=null && w.getCompletedAt() !=null)
                .toList();

        if(completed.isEmpty()){
            return WorkflowPerformanceSummaryResponse.builder().build();
        }

        var fastest = completed.stream()
                .min(
                        Comparator.comparingLong(
                                w->w.getCompletedAt() - w.getStartedAt()
                        )
                ).orElseThrow();

        var slowest = completed.stream()
                .max(
                        Comparator.comparingLong(
                                w->w.getCompletedAt() -w.getStartedAt()
                        )
                ).orElseThrow();

        double average = completed.stream()
                .mapToLong(
                        w->w.getCompletedAt() - w.getStartedAt()
                ).average()
                .orElse(0);

        return WorkflowPerformanceSummaryResponse.builder()
                .fastestWorkflow(fastest.getName())
                .fastestDuration(fastest.getCompletedAt()-fastest.getStartedAt())
                .slowestWorkflow(slowest.getName())
                .slowestDuration(slowest.getCompletedAt()- slowest.getStartedAt())
                .averageDuration(average)
                .completedWorkflowCount((long) completed.size())
                .build();
    }
    
    public List<WorkflowHealthResponse> getWorkflowHealth(){
        var completed = workflowRepository.findAll()
                .stream()
                .filter(workflow -> workflow.getStartedAt() !=null && workflow.getCompletedAt() !=null)
                .toList();
        
        if(completed.isEmpty()){
            return List.of();
        }
        
        double average = completed.stream()
                .mapToLong(workflow -> workflow.getCompletedAt() - workflow.getStartedAt())
                .average()
                .orElse(0);
        
        return completed.stream()
                .map(workflow -> {
                    long duration = workflow.getCompletedAt() -workflow.getStartedAt();
                    
                    String health;
                    String reason;
                    
                    if(duration <=average){
                        health="HEALTHY";
                        reason="Execution is within expected duration";
                    } else if (duration <= average*1.5) {
                        health = "WARNING";
                        reason = "Execution exceeded average duration.";
                    } else {
                        health = "CRITICAL";
                        reason = "Execution significantly exceeded average duration.";
                    }

                    return WorkflowHealthResponse.builder()
                            .workflowName(workflow.getName())
                            .durationMs(duration)
                            .health(health)
                            .reason(reason)
                            .build();
                })
                .toList();
    }
}
