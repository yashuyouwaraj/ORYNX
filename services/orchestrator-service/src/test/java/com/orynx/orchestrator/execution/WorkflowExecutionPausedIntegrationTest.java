package com.orynx.orchestrator.execution;

import com.orynx.orchestrator.event.dto.WorkflowExecutionPausedEvent;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class WorkflowExecutionPausedIntegrationTest {

    @Autowired
    private KafkaTemplate<String,Object> kafkaTemplate;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Test
    public void pausedEventShouldUpdateExecution() throws Exception {

        Workflow wf = Workflow.builder()
                .name("integration-pause-test")
                .goal("test")
                .status(WorkflowStatus.RUNNING)
                .build();

        Workflow saved = workflowRepository.save(wf);

        WorkflowExecution execution = WorkflowExecution.builder()
                .workflow(saved)
                .status(WorkflowStatus.RUNNING)
                .startedAt(System.currentTimeMillis())
                .build();

        workflowExecutionRepository.save(execution);

        WorkflowExecutionPausedEvent evt = WorkflowExecutionPausedEvent.builder()
                .workflowId(saved.getId())
                .workflowName(saved.getName())
                .lastCompletedExecutionOrder(2)
                .build();

        kafkaTemplate.send("workflow-execution-paused", evt);

        // wait for consumer to process
        Thread.sleep(2000);

        WorkflowExecution fetched = workflowExecutionRepository
                .findFirstByWorkflowIdOrderByStartedAtDesc(saved.getId())
                .orElseThrow();

        assertThat(fetched.getStatus()).isEqualTo(WorkflowStatus.PAUSED);
        assertThat(fetched.getLastCompletedExecutionOrder()).isEqualTo(2);
        assertThat(fetched.getCompletedAt()).isNull();
    }
}
