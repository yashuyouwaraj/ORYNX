package com.orynx.orchestrator.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowSchedulerTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private WorkflowScheduler workflowScheduler;

    @Test
    @DisplayName("Should claim and start due workflows")
    void shouldClaimAndStartDueWorkflows() {
        Workflow wf1 = Workflow.builder().id(101L).name("Due-1").status(WorkflowStatus.CREATED).scheduled(true).build();
        Workflow wf2 = Workflow.builder().id(102L).name("Due-2").status(WorkflowStatus.CREATED).scheduled(true).build();

        when(workflowRepository.findDueScheduledWorkflows(any(LocalDateTime.class)))
                .thenReturn(List.of(wf1, wf2));
        when(workflowRepository.claimScheduledWorkflow(101L)).thenReturn(1);
        when(workflowRepository.claimScheduledWorkflow(102L)).thenReturn(1);

        workflowScheduler.executeScheduledWorkflows();

        verify(workflowRepository).claimScheduledWorkflow(101L);
        verify(workflowService).startWorkflow(101L);
        verify(workflowRepository).claimScheduledWorkflow(102L);
        verify(workflowService).startWorkflow(102L);
        verify(workflowRepository, never()).releaseScheduledWorkflow(anyLong());
    }

    @Test
    @DisplayName("Should not start workflow if claim returns zero (already claimed by another instance)")
    void shouldNotStartIfClaimFails() {
        Workflow wf = Workflow.builder().id(201L).name("Contended-Workflow").status(WorkflowStatus.CREATED).scheduled(true).build();

        when(workflowRepository.findDueScheduledWorkflows(any(LocalDateTime.class)))
                .thenReturn(List.of(wf));
        when(workflowRepository.claimScheduledWorkflow(201L)).thenReturn(0);

        workflowScheduler.executeScheduledWorkflows();

        verify(workflowRepository).claimScheduledWorkflow(201L);
        verify(workflowService, never()).startWorkflow(201L);
        verify(workflowRepository, never()).releaseScheduledWorkflow(anyLong());
    }

    @Test
    @DisplayName("Should release claim when startWorkflow fails with exception")
    void shouldReleaseClaimWhenStartWorkflowFails() {
        Workflow wf = Workflow.builder().id(301L).name("Failing-Dispatch").status(WorkflowStatus.CREATED).scheduled(true).build();

        when(workflowRepository.findDueScheduledWorkflows(any(LocalDateTime.class)))
                .thenReturn(List.of(wf));
        when(workflowRepository.claimScheduledWorkflow(301L)).thenReturn(1);
        doThrow(new RuntimeException("Kafka dispatch connection error"))
                .when(workflowService).startWorkflow(301L);

        workflowScheduler.executeScheduledWorkflows();

        verify(workflowRepository).claimScheduledWorkflow(301L);
        verify(workflowService).startWorkflow(301L);
        verify(workflowRepository).releaseScheduledWorkflow(301L);
    }

    @Test
    @DisplayName("Failure in one workflow should not prevent subsequent eligible workflows from executing")
    void shouldContinueProcessingWhenOneWorkflowFails() {
        Workflow wf1 = Workflow.builder().id(401L).name("Fail-Dispatch").status(WorkflowStatus.CREATED).scheduled(true).build();
        Workflow wf2 = Workflow.builder().id(402L).name("Succeed-Dispatch").status(WorkflowStatus.CREATED).scheduled(true).build();

        when(workflowRepository.findDueScheduledWorkflows(any(LocalDateTime.class)))
                .thenReturn(List.of(wf1, wf2));
        when(workflowRepository.claimScheduledWorkflow(401L)).thenReturn(1);
        doThrow(new RuntimeException("Kafka error on wf1")).when(workflowService).startWorkflow(401L);
        when(workflowRepository.claimScheduledWorkflow(402L)).thenReturn(1);

        workflowScheduler.executeScheduledWorkflows();

        verify(workflowRepository).claimScheduledWorkflow(401L);
        verify(workflowService).startWorkflow(401L);
        verify(workflowRepository).releaseScheduledWorkflow(401L);

        verify(workflowRepository).claimScheduledWorkflow(402L);
        verify(workflowService).startWorkflow(402L);
    }

    @Test
    @DisplayName("Should do nothing when no due workflows exist")
    void shouldDoNothingWhenNoDueWorkflows() {
        when(workflowRepository.findDueScheduledWorkflows(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        workflowScheduler.executeScheduledWorkflows();

        verify(workflowRepository, never()).claimScheduledWorkflow(anyLong());
        verify(workflowService, never()).startWorkflow(anyLong());
    }

    @Test
    @DisplayName("Release claim exception should be safely caught and not terminate scheduler")
    void shouldHandleExceptionDuringReleaseGracefully() {
        Workflow wf = Workflow.builder().id(501L).name("Double-Fault").status(WorkflowStatus.CREATED).scheduled(true).build();

        when(workflowRepository.findDueScheduledWorkflows(any(LocalDateTime.class)))
                .thenReturn(List.of(wf));
        when(workflowRepository.claimScheduledWorkflow(501L)).thenReturn(1);
        doThrow(new RuntimeException("Start error")).when(workflowService).startWorkflow(501L);
        doThrow(new RuntimeException("DB error during release")).when(workflowRepository).releaseScheduledWorkflow(501L);

        // Must complete without throwing unhandled exception
        workflowScheduler.executeScheduledWorkflows();

        verify(workflowRepository).claimScheduledWorkflow(501L);
        verify(workflowService).startWorkflow(501L);
        verify(workflowRepository).releaseScheduledWorkflow(501L);
    }
}
