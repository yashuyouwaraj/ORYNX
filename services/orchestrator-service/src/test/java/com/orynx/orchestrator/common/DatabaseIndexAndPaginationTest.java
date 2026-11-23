package com.orynx.orchestrator.common;

import com.orynx.orchestrator.common.util.PaginationUtils;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.kafka.*;
import com.orynx.orchestrator.workflow.*;
import com.orynx.orchestrator.workflow.dto.DashboardSummaryResponse;
import com.orynx.orchestrator.workflow.dto.WorkflowAnalyticsResponse;
import com.orynx.orchestrator.workflow.dto.WorkflowPerformanceSummaryResponse;
import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.event.ExecutionTimelineService;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseIndexAndPaginationTest {

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowTaskRepository workflowTaskRepository;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private ExecutionEventRepository executionEventRepository;

    private WorkflowService createWorkflowService() {
        return new WorkflowService(
                workflowRepository,
                mock(KafkaProducer.class),
                mock(WorkflowEventPublisher.class),
                workflowTaskRepository,
                mock(WorkflowExecutionEngine.class),
                mock(ExecutionRequestProducer.class),
                workflowExecutionRepository,
                mock(WorkflowCancellationProducer.class),
                mock(WorkflowPauseProducer.class),
                mock(WorkflowResumeProducer.class)
        );
    }

    @Test
    @DisplayName("PaginationUtils enforces bounded page sizes, non-negative pages, and deterministic id tie-breaker sort")
    void paginationUtilsNormalizesBoundsAndSorting() {
        Sort defaultSort = Sort.by(Sort.Direction.DESC, "id");

        Pageable unpaged = PaginationUtils.normalize(Pageable.unpaged(), defaultSort);
        assertThat(unpaged.getPageNumber()).isEqualTo(0);
        assertThat(unpaged.getPageSize()).isEqualTo(PaginationUtils.DEFAULT_PAGE_SIZE);
        assertThat(unpaged.getSort().getOrderFor("id")).isNotNull();

        Pageable oversized = PaginationUtils.normalize(
                PageRequest.of(2, 5000, Sort.by(Sort.Direction.ASC, "name")),
                defaultSort
        );
        assertThat(oversized.getPageNumber()).isEqualTo(2);
        assertThat(oversized.getPageSize()).isEqualTo(PaginationUtils.MAX_PAGE_SIZE);
        assertThat(oversized.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(oversized.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("WorkflowService bounds workflow, status, task, and execution history pagination and uses GROUP BY summary")
    void workflowServiceBoundsPaginationAndEliminatesMultipleCountQueries() {
        WorkflowService service = createWorkflowService();
        Long userId = 10L;
        Workflow wf = Workflow.builder().id(1L).name("W1").userId(userId).status(WorkflowStatus.RUNNING).build();

        when(workflowRepository.findByUserIdOrderByIdDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wf)));
        service.getAllWorkflows(PageRequest.of(0, 999), userId);

        ArgumentCaptor<Pageable> wfPageable = ArgumentCaptor.forClass(Pageable.class);
        verify(workflowRepository).findByUserIdOrderByIdDesc(eq(userId), wfPageable.capture());
        assertThat(wfPageable.getValue().getPageSize()).isEqualTo(PaginationUtils.MAX_PAGE_SIZE);

        when(workflowRepository.findByUserIdAndStatus(eq(userId), eq(WorkflowStatus.RUNNING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wf)));
        service.getWorkflowsByStatus(WorkflowStatus.RUNNING, PageRequest.of(0, 250), userId);
        verify(workflowRepository).findByUserIdAndStatus(eq(userId), eq(WorkflowStatus.RUNNING), wfPageable.capture());
        assertThat(wfPageable.getValue().getPageSize()).isEqualTo(PaginationUtils.MAX_PAGE_SIZE);

        when(workflowRepository.findByIdAndUserId(1L, userId)).thenReturn(Optional.of(wf));
        when(workflowTaskRepository.findByWorkflowId(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(WorkflowTask.builder().id(10L).name("T1").executionOrder(1).build())));
        Page<WorkflowTask> tasks = service.getWorkflowTasks(1L, PageRequest.of(0, 300), userId);
        assertThat(tasks.getContent()).hasSize(1);
        verify(workflowTaskRepository).findByWorkflowId(eq(1L), wfPageable.capture());
        assertThat(wfPageable.getValue().getPageSize()).isEqualTo(PaginationUtils.MAX_PAGE_SIZE);
        assertThat(wfPageable.getValue().getSort().getOrderFor("executionOrder").getDirection()).isEqualTo(Sort.Direction.ASC);

        when(workflowExecutionRepository.findByWorkflowId(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(WorkflowExecution.builder().id(99L).status(WorkflowStatus.COMPLETED).startedAt(100L).build())));
        service.getWorkflowExecutionHistory(1L, PageRequest.of(0, 300), userId);
        verify(workflowExecutionRepository).findByWorkflowId(eq(1L), wfPageable.capture());
        assertThat(wfPageable.getValue().getPageSize()).isEqualTo(PaginationUtils.MAX_PAGE_SIZE);
        assertThat(wfPageable.getValue().getSort().getOrderFor("startedAt").getDirection()).isEqualTo(Sort.Direction.DESC);

        // Single GROUP BY query replaces 7 separate count queries
        when(workflowRepository.countGroupedByStatusByUserId(userId)).thenReturn(List.of(
                new Object[]{WorkflowStatus.RUNNING, 2L},
                new Object[]{WorkflowStatus.COMPLETED, 5L},
                new Object[]{WorkflowStatus.FAILED, 1L}
        ));
        DashboardSummaryResponse summary = service.getDashboardSummary(userId);
        assertThat(summary.getTotalWorkflows()).isEqualTo(8L);
        assertThat(summary.getRunningWorkflows()).isEqualTo(2L);
        assertThat(summary.getCompletedWorkflows()).isEqualTo(5L);
        assertThat(summary.getFailedWorkflows()).isEqualTo(1L);
        assertThat(summary.getPausedWorkflows()).isEqualTo(0L);
        verify(workflowRepository, never()).countByUserIdAndStatus(anyLong(), any());
    }

    @Test
    @DisplayName("ExecutionTimelineService enforces ownership and bounded pagination")
    void timelineServiceEnforcesOwnershipAndBoundedPagination() {
        WorkflowService workflowService = mock(WorkflowService.class);
        ExecutionTimelineService timelineService = new ExecutionTimelineService(executionEventRepository, workflowService);

        when(executionEventRepository.findByWorkflowId(eq(5L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        ExecutionEvent.builder().id(1L).workflowId(5L).taskName("T1").status("COMPLETED").timestamp(500L).build()
                )));

        var page = timelineService.getWorkflowTimeline(5L, PageRequest.of(0, 500));
        assertThat(page.getContent()).hasSize(1);
        verify(workflowService).getWorkflow(5L);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(executionEventRepository).findByWorkflowId(eq(5L), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(PaginationUtils.MAX_PAGE_SIZE);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("timestamp").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("WorkflowAnalyticsService and WorkflowPerformanceService use DB aggregates instead of findAll full-table scans")
    void analyticsAndPerformanceServicesAvoidFindAll() {
        WorkflowAnalyticsService analyticsService = new WorkflowAnalyticsService(
                workflowRepository, executionEventRepository, workflowTaskRepository);
        when(executionEventRepository.count()).thenReturn(20L);
        when(workflowRepository.countGroupedByStatus()).thenReturn(List.of(
                new Object[]{WorkflowStatus.COMPLETED, 3L},
                new Object[]{WorkflowStatus.FAILED, 1L},
                new Object[]{WorkflowStatus.RUNNING, 1L}
        ));
        when(workflowTaskRepository.count()).thenReturn(12L);
        when(workflowTaskRepository.countByStatus(TaskStatus.COMPLETED)).thenReturn(9L);
        when(workflowTaskRepository.findAverageCompletedDurationMs()).thenReturn(250.0);

        WorkflowAnalyticsResponse analytics = analyticsService.getAnalytics();
        assertThat(analytics.getTotalWorkflows()).isEqualTo(5L);
        assertThat(analytics.getCompletedWorkflows()).isEqualTo(3L);
        assertThat(analytics.getRunningWorkflows()).isEqualTo(1L);
        assertThat(analytics.getWorkflowSuccessRate()).isEqualTo(60.0);
        assertThat(analytics.getExecutedTaskCount()).isEqualTo(9L);
        assertThat(analytics.getAverageExecutionTime()).isEqualTo(250.0);
        verify(workflowRepository, never()).findAll();
        verify(workflowTaskRepository, never()).findAll();

        WorkflowPerformanceService performanceService = new WorkflowPerformanceService(workflowRepository);
        Workflow fast = Workflow.builder().id(1L).name("Fast").startedAt(100L).completedAt(200L).build();
        Workflow slow = Workflow.builder().id(2L).name("Slow").startedAt(100L).completedAt(600L).build();
        when(workflowRepository.findCompletedWorkflowDurationAggregates())
                .thenReturn(List.<Object[]>of(new Object[]{2L, 100L, 500L, 300.0}));
        when(workflowRepository.findCompletedWorkflowsOrderByDurationAsc(any()))
                .thenReturn(List.of(fast));
        when(workflowRepository.findCompletedWorkflowsOrderByDurationDesc(any()))
                .thenReturn(List.of(slow));

        WorkflowPerformanceSummaryResponse perfSummary = performanceService.getPerformanceSummary();
        assertThat(perfSummary.getFastestWorkflow()).isEqualTo("Fast");
        assertThat(perfSummary.getFastestDuration()).isEqualTo(100L);
        assertThat(perfSummary.getSlowestWorkflow()).isEqualTo("Slow");
        assertThat(perfSummary.getSlowestDuration()).isEqualTo(500L);
        assertThat(perfSummary.getCompletedWorkflowCount()).isEqualTo(2L);
        verify(workflowRepository, never()).findAll();
    }
}
