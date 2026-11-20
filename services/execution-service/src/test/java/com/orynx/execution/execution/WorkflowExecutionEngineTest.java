package com.orynx.execution.execution;

import com.orynx.execution.event.dto.TaskCompletedEvent;
import com.orynx.execution.event.dto.TaskStartedEvent;
import com.orynx.execution.event.dto.WorkflowCompletedEvent;
import com.orynx.execution.kafka.ExecutionEventProducer;
import com.orynx.execution.workflow.event.WorkflowExecutionRequestEvent;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionEngineTest {

    @Mock
    private ExecutionEventProducer executionEventProducer;

    @Test
    void shouldStopExecutionAndPublishFailureWhenTaskExhaustsRetries() {
        AtomicInteger callCount = new AtomicInteger();
        // Call 1 (Task 1 attempt 1) -> true; Calls 2,3,4 (Task 2 maxRetries=2 -> 3 attempts) -> false
        TaskExecutor taskExecutor = new TaskExecutor(() -> callCount.incrementAndGet() == 1, 0L);
        WorkflowExecutionEngine engine = new WorkflowExecutionEngine(
                taskExecutor,
                executionEventProducer,
                new WorkflowCancellationRegistry(),
                new WorkflowPauseRegistry()
        );

        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(42L)
                .workflowName("ETL Pipeline")
                .tasks(List.of(
                        TaskExecutionRequest.builder().name("Task 1").executionOrder(1).maxRetries(2).build(),
                        TaskExecutionRequest.builder().name("Task 2").executionOrder(2).maxRetries(2).build(),
                        TaskExecutionRequest.builder().name("Task 3").executionOrder(3).maxRetries(2).build()
                ))
                .build();

        engine.execute(event);

        ArgumentCaptor<TaskStartedEvent> startedCaptor = ArgumentCaptor.forClass(TaskStartedEvent.class);
        verify(executionEventProducer, times(2)).publishTaskStarted(startedCaptor.capture());
        assertThat(startedCaptor.getAllValues())
                .extracting(TaskStartedEvent::getTaskName)
                .containsExactly("Task 1", "Task 2");

        ArgumentCaptor<TaskCompletedEvent> completedTaskCaptor = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(executionEventProducer, times(2)).publishTaskCompleted(completedTaskCaptor.capture());
        List<TaskCompletedEvent> completedTasks = completedTaskCaptor.getAllValues();

        assertThat(completedTasks.get(0).getTaskName()).isEqualTo("Task 1");
        assertThat(completedTasks.get(0).isSuccess()).isTrue();
        assertThat(completedTasks.get(0).getAttempts()).isEqualTo(1);

        assertThat(completedTasks.get(1).getTaskName()).isEqualTo("Task 2");
        assertThat(completedTasks.get(1).isSuccess()).isFalse();
        assertThat(completedTasks.get(1).getAttempts()).isEqualTo(3);

        ArgumentCaptor<WorkflowCompletedEvent> completedWorkflowCaptor = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
        verify(executionEventProducer, times(1)).publishWorkflowCompleted(completedWorkflowCaptor.capture());
        WorkflowCompletedEvent workflowCompleted = completedWorkflowCaptor.getValue();
        assertThat(workflowCompleted.getWorkflowId()).isEqualTo(42L);
        assertThat(workflowCompleted.isSuccess()).isFalse();
    }

    @Test
    void shouldCompleteWorkflowWhenTaskSucceedsAfterRetry() {
        AtomicInteger callCount = new AtomicInteger();
        // Call 1 (Task 1 attempt 1) -> false; Call 2 (Task 1 attempt 2) -> true; Call 3 (Task 2 attempt 1) -> true
        TaskExecutor taskExecutor = new TaskExecutor(() -> callCount.incrementAndGet() != 1, 0L);
        WorkflowExecutionEngine engine = new WorkflowExecutionEngine(
                taskExecutor,
                executionEventProducer,
                new WorkflowCancellationRegistry(),
                new WorkflowPauseRegistry()
        );

        WorkflowExecutionRequestEvent event = WorkflowExecutionRequestEvent.builder()
                .workflowId(77L)
                .workflowName("Analytics Job")
                .tasks(List.of(
                        TaskExecutionRequest.builder().name("Task 1").executionOrder(1).maxRetries(2).build(),
                        TaskExecutionRequest.builder().name("Task 2").executionOrder(2).maxRetries(2).build()
                ))
                .build();

        engine.execute(event);

        ArgumentCaptor<TaskCompletedEvent> completedTaskCaptor = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(executionEventProducer, times(2)).publishTaskCompleted(completedTaskCaptor.capture());
        List<TaskCompletedEvent> completedTasks = completedTaskCaptor.getAllValues();

        assertThat(completedTasks.get(0).getTaskName()).isEqualTo("Task 1");
        assertThat(completedTasks.get(0).isSuccess()).isTrue();
        assertThat(completedTasks.get(0).getAttempts()).isEqualTo(2);

        assertThat(completedTasks.get(1).getTaskName()).isEqualTo("Task 2");
        assertThat(completedTasks.get(1).isSuccess()).isTrue();
        assertThat(completedTasks.get(1).getAttempts()).isEqualTo(1);

        ArgumentCaptor<WorkflowCompletedEvent> completedWorkflowCaptor = ArgumentCaptor.forClass(WorkflowCompletedEvent.class);
        verify(executionEventProducer, times(1)).publishWorkflowCompleted(completedWorkflowCaptor.capture());
        WorkflowCompletedEvent workflowCompleted = completedWorkflowCaptor.getValue();
        assertThat(workflowCompleted.getWorkflowId()).isEqualTo(77L);
        assertThat(workflowCompleted.isSuccess()).isTrue();
    }
}
