package com.orynx.orchestrator.workflow.event;

import com.orynx.orchestrator.common.util.PaginationUtils;
import com.orynx.orchestrator.workflow.WorkflowService;
import com.orynx.orchestrator.workflow.event.dto.ExecutionTimelineResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExecutionTimelineService {
    private static final Sort DEFAULT_TIMELINE_SORT = Sort.by(
            Sort.Order.desc("timestamp"),
            Sort.Order.desc("id")
    );

    private final ExecutionEventRepository executionEventRepository;
    private final WorkflowService workflowService;

    public ExecutionTimelineService(ExecutionEventRepository executionEventRepository) {
        this(executionEventRepository, null);
    }

    @Autowired
    public ExecutionTimelineService(
            ExecutionEventRepository executionEventRepository,
            WorkflowService workflowService
    ) {
        this.executionEventRepository = executionEventRepository;
        this.workflowService = workflowService;
    }

    public Page<ExecutionTimelineResponse> getWorkflowTimeline(Long workflowId, Pageable pageable) {
        if (workflowService != null) {
            workflowService.getWorkflow(workflowId);
        }
        Pageable normalized = PaginationUtils.normalize(pageable, DEFAULT_TIMELINE_SORT);
        return executionEventRepository.findByWorkflowId(workflowId, normalized)
                .map(event -> ExecutionTimelineResponse.builder()
                        .taskName(event.getTaskName())
                        .status(event.getStatus())
                        .timestamp(event.getTimestamp())
                        .build());
    }

    public List<ExecutionTimelineResponse> getWorkflowTimeline(Long workflowId) {
        return getWorkflowTimeline(workflowId, PageRequest.of(0, PaginationUtils.DEFAULT_PAGE_SIZE))
                .getContent();
    }
}
