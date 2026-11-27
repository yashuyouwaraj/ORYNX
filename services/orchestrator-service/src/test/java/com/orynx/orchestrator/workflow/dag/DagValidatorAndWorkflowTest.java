package com.orynx.orchestrator.workflow.dag;

import com.orynx.orchestrator.execution.ExecutionEventConsumer;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.kafka.ExecutionRequestProducer;
import com.orynx.orchestrator.kafka.KafkaProducer;
import com.orynx.orchestrator.kafka.WorkflowCancellationProducer;
import com.orynx.orchestrator.kafka.WorkflowPauseProducer;
import com.orynx.orchestrator.kafka.WorkflowResumeProducer;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowEventPublisher;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowService;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowTaskRequest;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DagValidatorAndWorkflowTest {

    private final DagValidator validator = new DagValidator();

    @Test
    void validatesDiamondDagAndAssignsDeterministicTopologicalOrder() {
        List<CreateWorkflowTaskRequest> tasks = List.of(
                CreateWorkflowTaskRequest.builder().taskKey("D").name("Join").taskType("TRANSFORM").dependsOn(List.of("B", "C")).build(),
                CreateWorkflowTaskRequest.builder().taskKey("A").name("Root").taskType("NOOP").dependsOn(List.of()).build(),
                CreateWorkflowTaskRequest.builder().taskKey("B").name("Left").taskType("ECHO").dependsOn(List.of("A")).build(),
                CreateWorkflowTaskRequest.builder().taskKey("C").name("Right").taskType("ECHO").dependsOn(List.of("A")).build()
        );

        DagValidator.ValidatedDag dag = validator.validateAndNormalize(tasks);

        assertThat(dag.edgeCount()).isEqualTo(4);
        assertThat(dag.maxDepth()).isEqualTo(3);
        List<String> keys = dag.topologicalTasks().stream().map(DagValidator.ValidatedTaskSpec::taskKey).toList();
        assertThat(keys.get(0)).isEqualTo("A");
        assertThat(keys.get(3)).isEqualTo("D");
        assertThat(keys.subList(1, 3)).containsExactlyInAnyOrder("B", "C");
    }

    @Test
    void rejectsCyclesSelfDependenciesDuplicateKeysMissingDepsAndBounds() {
        // 2-node cycle
        assertThatThrownBy(() -> validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder().taskKey("A").name("A").dependsOn(List.of("B")).build(),
                CreateWorkflowTaskRequest.builder().taskKey("B").name("B").dependsOn(List.of("A")).build()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cycle");

        // Self-dependency
        assertThatThrownBy(() -> validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder().taskKey("A").name("A").dependsOn(List.of("A")).build()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Self-dependency");

        // Duplicate key
        assertThatThrownBy(() -> validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder().taskKey("A").name("First").dependsOn(List.of()).build(),
                CreateWorkflowTaskRequest.builder().taskKey("A").name("Second").dependsOn(List.of()).build()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate taskKey");

        // Missing dependency
        assertThatThrownBy(() -> validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder().taskKey("A").name("A").dependsOn(List.of("MISSING")).build()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nonexistent");

        // Unsupported task type
        assertThatThrownBy(() -> validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder().taskKey("A").name("A").taskType("SHELL_EXEC").dependsOn(List.of()).build()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported taskType");

        // Exceeds max nodes
        List<CreateWorkflowTaskRequest> tooMany = new ArrayList<>();
        for (int i = 0; i <= DagValidator.MAX_NODES; i++) {
            tooMany.add(CreateWorkflowTaskRequest.builder().taskKey("n" + i).name("N" + i).dependsOn(List.of()).build());
        }
        assertThatThrownBy(() -> validator.validateAndNormalize(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum node limit");

        // Exceeds max depth
        List<CreateWorkflowTaskRequest> tooDeep = new ArrayList<>();
        for (int i = 0; i <= DagValidator.MAX_DEPTH; i++) {
            tooDeep.add(CreateWorkflowTaskRequest.builder()
                    .taskKey("d" + i)
                    .name("D" + i)
                    .dependsOn(i == 0 ? List.of() : List.of("d" + (i - 1)))
                    .build());
        }
        assertThatThrownBy(() -> validator.validateAndNormalize(tooDeep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum depth");
    }

    @Test
    void supportsDisconnectedDagComponentsAndLegacyLinearFallback() {
        // Disconnected components: A -> B and C -> D
        DagValidator.ValidatedDag disconnected = validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder().taskKey("A").name("A").dependsOn(List.of()).build(),
                CreateWorkflowTaskRequest.builder().taskKey("B").name("B").dependsOn(List.of("A")).build(),
                CreateWorkflowTaskRequest.builder().taskKey("C").name("C").dependsOn(List.of()).build(),
                CreateWorkflowTaskRequest.builder().taskKey("D").name("D").dependsOn(List.of("C")).build()
        ));
        assertThat(disconnected.topologicalTasks()).hasSize(4);
        assertThat(disconnected.maxDepth()).isEqualTo(2);

        // Legacy linear tasks without dependsOn
        DagValidator.ValidatedDag linear = validator.validateAndNormalize(List.of(
                CreateWorkflowTaskRequest.builder().name("Step 2").executionOrder(2).build(),
                CreateWorkflowTaskRequest.builder().name("Step 1").executionOrder(1).build()
        ));
        assertThat(linear.topologicalTasks().get(0).name()).isEqualTo("Step 1");
        assertThat(linear.topologicalTasks().get(0).dependsOn()).isEmpty();
        assertThat(linear.topologicalTasks().get(1).name()).isEqualTo("Step 2");
        assertThat(linear.topologicalTasks().get(1).dependsOn()).containsExactly("task-1");
    }

    @Test
    void workflowServiceRejectsCyclicDagBeforePersisting() {
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        WorkflowService service = new WorkflowService(
                wfRepo, mock(KafkaProducer.class), mock(WorkflowEventPublisher.class),
                mock(WorkflowTaskRepository.class), mock(WorkflowExecutionEngine.class),
                mock(ExecutionRequestProducer.class), mock(WorkflowExecutionRepository.class),
                mock(WorkflowCancellationProducer.class), mock(WorkflowPauseProducer.class),
                mock(WorkflowResumeProducer.class)
        );

        CreateWorkflowRequest req = new CreateWorkflowRequest();
        req.setName("Cyclic-WF");
        req.setGoal("Must fail validation");
        req.setTasks(List.of(
                CreateWorkflowTaskRequest.builder().taskKey("A").name("A").dependsOn(List.of("B")).build(),
                CreateWorkflowTaskRequest.builder().taskKey("B").name("B").dependsOn(List.of("A")).build()
        ));

        assertThatThrownBy(() -> service.createWorkflow(req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cycle");
        verify(wfRepo, never()).save(any());
    }

    @Test
    void executionEventConsumerPersistsSkippedStatusOutputAndRedactedErrorByTaskKey() {
        WorkflowRepository wfRepo = mock(WorkflowRepository.class);
        WorkflowTaskRepository taskRepo = mock(WorkflowTaskRepository.class);
        WorkflowExecutionRepository execRepo = mock(WorkflowExecutionRepository.class);
        ExecutionEventConsumer consumer = new ExecutionEventConsumer(wfRepo, taskRepo, execRepo, mock(WorkflowEventPublisher.class));

        Workflow wf = Workflow.builder().id(10L).name("DAG-WF").status(WorkflowStatus.RUNNING).build();
        WorkflowTask task = WorkflowTask.builder().id(2L).taskKey("D").name("Join").executionOrder(4).status(TaskStatus.PENDING).workflow(wf).build();
        WorkflowExecution exec = WorkflowExecution.builder().id(50L).workflow(wf).status(WorkflowStatus.RUNNING).executionGeneration(1L).build();

        when(taskRepo.findByWorkflowIdAndTaskKey(10L, "D")).thenReturn(Optional.of(task));
        when(wfRepo.findById(10L)).thenReturn(Optional.of(wf));
        when(execRepo.findFirstByWorkflowIdOrderByStartedAtDesc(10L)).thenReturn(Optional.of(exec));

        consumer.consumeTaskCompleted(TaskCompletedEvent.builder()
                .workflowId(10L)
                .workflowName("DAG-WF")
                .taskKey("D")
                .taskName("Join")
                .executionOrder(4)
                .success(false)
                .skipped(true)
                .generation(1L)
                .errorMessage("Skipped due to failed upstream B password=secret123")
                .build());

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SKIPPED);
        assertThat(task.getErrorMessage()).doesNotContain("secret123").contains("[REDACTED]");
    }
}
