package com.orynx.orchestrator.kafka;

import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.task.TaskEventProducer;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrchestratorKafkaIntegrationTest {

    @Autowired
    private KafkaTemplate<String, TaskExecutionEvent> taskEventKafkaTemplate;

    @Autowired
    private CommonErrorHandler kafkaCommonErrorHandler;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ExecutionEventRepository executionEventRepository;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    @DisplayName("Real Kafka broker handles poison record via DefaultErrorHandler, processes keyed task events, and deduplicates redelivery")
    void kafkaEndToEndPoisonRecoveryAndDuplicateDeduplication() throws Exception {
        assertThat(kafkaCommonErrorHandler).isInstanceOf(DefaultErrorHandler.class);

        Workflow wf = workflowRepository.save(Workflow.builder()
                .name("kafka-e2e-reliability-test")
                .goal("Verify poison handling, key metadata, and deduplication")
                .status(WorkflowStatus.RUNNING)
                .startedAt(System.currentTimeMillis())
                .build());

        Long workflowId = wf.getId();
        String key = String.valueOf(workflowId);
        // Ensure clean timeline state if PostgreSQL workflows sequence was reset by schema migration tests
        List<ExecutionEvent> existing = executionEventRepository.findByWorkflowIdOrderByTimestampDesc(workflowId);
        if (!existing.isEmpty()) {
            executionEventRepository.deleteAllInBatch(existing);
        }
        long uniqueTaskId = System.currentTimeMillis();

        // 1. Send a malformed poison JSON payload to task-execution-events
        var rawFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
        try {
            KafkaTemplate<String, String> rawTemplate = new KafkaTemplate<>(rawFactory);
            rawTemplate.send(TaskEventProducer.TASK_EXECUTION_EVENTS_TOPIC, key, "{invalid-json-poison")
                    .get(10, TimeUnit.SECONDS);
        } finally {
            rawFactory.destroy();
        }

        // 2. Send a valid keyed TaskExecutionEvent immediately after the poison record
        TaskExecutionEvent runningEvent = TaskExecutionEvent.builder()
                .workflowId(workflowId)
                .taskId(uniqueTaskId)
                .taskName("Step A")
                .status("RUNNING")
                .build();

        SendResult<String, TaskExecutionEvent> sendResult = taskEventKafkaTemplate
                .send(TaskEventProducer.TASK_EXECUTION_EVENTS_TOPIC, key, runningEvent)
                .get(10, TimeUnit.SECONDS);
        assertThat(sendResult.getRecordMetadata().hasOffset()).isTrue();
        assertThat(sendResult.getRecordMetadata().serializedKeySize()).isGreaterThan(0);

        // Wait until TaskEventConsumer processes past the poison record and persists Step A RUNNING
        List<ExecutionEvent> events = awaitEventCount(workflowId, 1, 10000L);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo("RUNNING");

        // 3. Redeliver duplicate RUNNING event over real Kafka -> must NOT insert duplicate row
        taskEventKafkaTemplate
                .send(TaskEventProducer.TASK_EXECUTION_EVENTS_TOPIC, key, runningEvent)
                .get(10, TimeUnit.SECONDS);

        // 4. Deliver COMPLETED event -> must be persisted once
        TaskExecutionEvent completedEvent = TaskExecutionEvent.builder()
                .workflowId(workflowId)
                .taskId(uniqueTaskId)
                .taskName("Step A")
                .status("COMPLETED")
                .build();
        taskEventKafkaTemplate
                .send(TaskEventProducer.TASK_EXECUTION_EVENTS_TOPIC, key, completedEvent)
                .get(10, TimeUnit.SECONDS);

        List<ExecutionEvent> finalEvents = awaitEventCount(workflowId, 2, 10000L);
        assertThat(finalEvents)
                .hasSize(2)
                .extracting(ExecutionEvent::getStatus)
                .containsExactlyInAnyOrder("COMPLETED", "RUNNING");
    }

    private List<ExecutionEvent> awaitEventCount(Long workflowId, int expectedMin, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<ExecutionEvent> events;
        do {
            events = executionEventRepository.findByWorkflowIdOrderByTimestampDesc(workflowId);
            if (events.size() >= expectedMin) {
                // Give a brief settle window for any duplicate redelivery already queued
                Thread.sleep(250L);
                return executionEventRepository.findByWorkflowIdOrderByTimestampDesc(workflowId);
            }
            Thread.sleep(200L);
        } while (System.currentTimeMillis() < deadline);
        return executionEventRepository.findByWorkflowIdOrderByTimestampDesc(workflowId);
    }
}
