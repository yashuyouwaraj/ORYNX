package com.orynx.orchestrator;

import com.orynx.orchestrator.ai.AiWorkflowService;
import com.orynx.orchestrator.ai.dto.AiWorkflowRequest;
import com.orynx.orchestrator.ai.dto.AiWorkflowResponse;
import com.orynx.orchestrator.common.config.PrometheusMetricsServer;
import com.orynx.orchestrator.common.metrics.WorkflowMetrics;
import com.orynx.orchestrator.execution.ExecutionEventConsumer;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.execution.event.TaskCompletedEvent;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.integration.planning.service.PlanningIntegrationService;
import com.orynx.orchestrator.kafka.ExecutionRequestProducer;
import com.orynx.orchestrator.kafka.KafkaProducer;
import com.orynx.orchestrator.kafka.WorkflowCancellationProducer;
import com.orynx.orchestrator.kafka.WorkflowPauseProducer;
import com.orynx.orchestrator.kafka.WorkflowResumeProducer;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowEventProducer;
import com.orynx.orchestrator.workflow.WorkflowEventPublisher;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowService;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityMetricsAndConfigTest {

    private PrometheusMeterRegistry registry;
    private WorkflowMetrics metrics;
    private WorkflowRepository workflowRepository;
    private WorkflowTaskRepository workflowTaskRepository;
    private WorkflowExecutionRepository workflowExecutionRepository;
    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowService workflowService;
    private AiWorkflowService aiWorkflowService;
    private PlanningIntegrationService planningIntegrationService;
    private ExecutionEventConsumer executionEventConsumer;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new WorkflowMetrics(registry);

        workflowRepository = mock(WorkflowRepository.class);
        workflowTaskRepository = mock(WorkflowTaskRepository.class);
        workflowExecutionRepository = mock(WorkflowExecutionRepository.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        planningIntegrationService = mock(PlanningIntegrationService.class);

        workflowService = new WorkflowService(
                workflowRepository,
                mock(KafkaProducer.class),
                workflowEventPublisher,
                workflowTaskRepository,
                mock(WorkflowExecutionEngine.class),
                mock(ExecutionRequestProducer.class),
                workflowExecutionRepository,
                mock(WorkflowCancellationProducer.class),
                mock(WorkflowPauseProducer.class),
                mock(WorkflowResumeProducer.class)
        );
        ReflectionTestUtils.setField(workflowService, "workflowMetrics", metrics);

        aiWorkflowService = new AiWorkflowService(
                planningIntegrationService,
                workflowRepository,
                workflowTaskRepository,
                mock(WorkflowEventProducer.class)
        );
        ReflectionTestUtils.setField(aiWorkflowService, "workflowMetrics", metrics);

        executionEventConsumer = new ExecutionEventConsumer(
                workflowRepository,
                workflowTaskRepository,
                workflowExecutionRepository,
                workflowEventPublisher
        );
        ReflectionTestUtils.setField(executionEventConsumer, "workflowMetrics", metrics);
    }

    @Test
    void preRegistration_exposesAllBoundedSeriesAndHistogramBucketsBeforeFirstEvent() {
        String scraped = registry.scrape();

        assertThat(scraped).contains("orynx_workflows_total 0.0");
        assertThat(scraped).contains("orynx_workflows_started_total 0.0");
        assertThat(scraped).contains("orynx_workflows_retried_total 0.0");
        assertThat(scraped).contains("orynx_workflows_cancelled_total 0.0");
        assertThat(scraped).contains("orynx_workflows_completed_total{status=\"COMPLETED\"} 0.0");
        assertThat(scraped).contains("orynx_workflows_completed_total{status=\"FAILED\"} 0.0");
        assertThat(scraped).contains("orynx_workflows_completed_total{status=\"CANCELLED\"} 0.0");
        assertThat(scraped).contains("orynx_workflows_duration_seconds_bucket{status=\"COMPLETED\"");
        assertThat(scraped).contains("orynx_workflows_duration_seconds_count{status=\"COMPLETED\"} 0");
        assertThat(scraped).contains("orynx_workflows_duration_seconds_count{status=\"FAILED\"} 0");
        assertThat(scraped).contains("orynx_tasks_executed_total{status=\"COMPLETED\"} 0.0");
        assertThat(scraped).contains("orynx_tasks_executed_total{status=\"FAILED\"} 0.0");
        assertThat(scraped).doesNotContain("userId", "workflowId", "taskId", "executionId", "requestId");
    }

    @Test
    void workflowCreation_countsBothStandardAndAiCreatedWorkflows() {
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> {
            Workflow wf = inv.getArgument(0);
            wf.setId(101L);
            return wf;
        });

        CreateWorkflowRequest stdReq = new CreateWorkflowRequest();
        stdReq.setName("Standard Workflow");
        stdReq.setGoal("Run standard flow");
        workflowService.createWorkflow(stdReq, 1L);

        when(planningIntegrationService.generateWorkflowPlan("AI Goal"))
                .thenReturn(AiWorkflowResponse.builder()
                        .workflowName("AI Generated")
                        .tasks(List.of("Task A", "Task B"))
                        .build());

        AiWorkflowRequest aiReq = new AiWorkflowRequest();
        aiReq.setName("AI Workflow");
        aiReq.setGoal("AI Goal");
        aiWorkflowService.createdAiWorkflow(aiReq, 1L);

        assertThat(registry.find("orynx.workflows.created").counter().count()).isEqualTo(2.0);
    }

    @Test
    void executionEventConsumer_recordsTaskMetricsAndSuppressesDuplicatesAndFencedEvents() {
        Workflow runningWf = Workflow.builder()
                .id(10L)
                .name("Distributed Workflow")
                .status(WorkflowStatus.RUNNING)
                .userId(1L)
                .build();
        WorkflowExecution activeExec = WorkflowExecution.builder()
                .id(50L)
                .workflow(runningWf)
                .status(WorkflowStatus.RUNNING)
                .executionGeneration(2L)
                .startedAt(1000L)
                .build();
        WorkflowTask task1 = WorkflowTask.builder()
                .id(100L)
                .name("Task 1")
                .executionOrder(1)
                .status(TaskStatus.RUNNING)
                .workflow(runningWf)
                .build();
        WorkflowTask task2 = WorkflowTask.builder()
                .id(101L)
                .name("Task 2")
                .executionOrder(2)
                .status(TaskStatus.RUNNING)
                .workflow(runningWf)
                .build();

        when(workflowRepository.findById(10L)).thenReturn(Optional.of(runningWf));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(10L))
                .thenReturn(Optional.of(activeExec));
        when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(10L, 1))
                .thenReturn(Optional.of(task1));
        when(workflowTaskRepository.findByWorkflowIdAndExecutionOrder(10L, 2))
                .thenReturn(Optional.of(task2));

        // 1. Valid task 1 completion (gen=2) -> increments COMPLETED to 1
        TaskCompletedEvent validCompleted = TaskCompletedEvent.builder()
                .workflowId(10L)
                .workflowName("Distributed Workflow")
                .taskName("Task 1")
                .executionOrder(1)
                .success(true)
                .attempts(1)
                .generation(2L)
                .build();
        executionEventConsumer.consumeTaskCompleted(validCompleted);

        assertThat(registry.find("orynx.tasks.executed").tag("status", "COMPLETED").counter().count())
                .isEqualTo(1.0);

        // 2. Duplicate delivery of task 1 completion -> suppressed, stays at 1
        executionEventConsumer.consumeTaskCompleted(validCompleted);
        assertThat(registry.find("orynx.tasks.executed").tag("status", "COMPLETED").counter().count())
                .isEqualTo(1.0);

        // 3. Stale/fenced generation event for task 2 (gen=1 < active gen=2) -> rejected, FAILED stays 0
        TaskCompletedEvent fencedFailure = TaskCompletedEvent.builder()
                .workflowId(10L)
                .workflowName("Distributed Workflow")
                .taskName("Task 2")
                .executionOrder(2)
                .success(false)
                .attempts(3)
                .generation(1L)
                .build();
        executionEventConsumer.consumeTaskCompleted(fencedFailure);
        assertThat(registry.find("orynx.tasks.executed").tag("status", "FAILED").counter().count())
                .isEqualTo(0.0);

        // 4. Valid task 2 failure (gen=2) -> increments FAILED to 1
        TaskCompletedEvent validFailure = TaskCompletedEvent.builder()
                .workflowId(10L)
                .workflowName("Distributed Workflow")
                .taskName("Task 2")
                .executionOrder(2)
                .success(false)
                .attempts(3)
                .generation(2L)
                .build();
        executionEventConsumer.consumeTaskCompleted(validFailure);
        assertThat(registry.find("orynx.tasks.executed").tag("status", "FAILED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void workflowLifecycle_recordsStartCancelRetryAndAccurateRetryDurationWithoutStaleTimestamp() {
        long staleOriginalStart = System.currentTimeMillis() - 3_600_000L; // 1 hour ago
        Workflow wf = Workflow.builder()
                .id(20L)
                .name("Retryable Workflow")
                .status(WorkflowStatus.CREATED)
                .userId(7L)
                .build();
        WorkflowTask t1 = WorkflowTask.builder()
                .id(201L)
                .name("Step 1")
                .executionOrder(1)
                .status(TaskStatus.PENDING)
                .maxRetries(2)
                .workflow(wf)
                .build();

        when(workflowRepository.findByIdAndUserIdForUpdate(20L, 7L)).thenReturn(Optional.of(wf));
        when(workflowRepository.findByIdAndUserId(20L, 7L)).thenReturn(Optional.of(wf));
        when(workflowRepository.findById(20L)).thenReturn(Optional.of(wf));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(20L)).thenReturn(List.of(t1));

        // 1. Start workflow
        workflowService.startWorkflow(20L, 7L);
        assertThat(registry.find("orynx.workflows.started").counter().count()).isEqualTo(1.0);

        // Simulate initial run failing 1 hour ago
        wf.setStartedAt(staleOriginalStart);
        WorkflowExecution gen1Exec = WorkflowExecution.builder()
                .id(301L)
                .workflow(wf)
                .status(WorkflowStatus.RUNNING)
                .startedAt(System.currentTimeMillis() - 400L)
                .executionGeneration(1L)
                .build();
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(20L))
                .thenReturn(Optional.of(gen1Exec));

        WorkflowCompletedEvent failEvent = new WorkflowCompletedEvent(20L, "Retryable Workflow", false, 1L, "ev-1");
        executionEventConsumer.consumeWorkflowCompleted(failEvent);

        assertThat(registry.find("orynx.workflows.completed").tag("status", "FAILED").counter().count())
                .isEqualTo(1.0);
        // Duplicate failure event must not double-count
        executionEventConsumer.consumeWorkflowCompleted(failEvent);
        assertThat(registry.find("orynx.workflows.completed").tag("status", "FAILED").counter().count())
                .isEqualTo(1.0);

        // 2. Retry failed workflow -> resets startedAt and increments retried + started counters
        long beforeRetry = System.currentTimeMillis();
        workflowService.retryWorkflow(20L, 7L);
        assertThat(registry.find("orynx.workflows.retried").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("orynx.workflows.started").counter().count()).isEqualTo(2.0);
        assertThat(wf.getStartedAt()).isGreaterThanOrEqualTo(beforeRetry);

        // Fenced gen=1 completion arriving during gen=2 must be ignored
        WorkflowExecution gen2Exec = WorkflowExecution.builder()
                .id(302L)
                .workflow(wf)
                .status(WorkflowStatus.RUNNING)
                .startedAt(System.currentTimeMillis() - 250L)
                .executionGeneration(2L)
                .build();
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(20L))
                .thenReturn(Optional.of(gen2Exec));

        executionEventConsumer.consumeWorkflowCompleted(
                new WorkflowCompletedEvent(20L, "Retryable Workflow", true, 1L, "ev-stale")
        );
        assertThat(registry.find("orynx.workflows.completed").tag("status", "COMPLETED").counter().count())
                .isEqualTo(0.0);

        // Valid gen=2 completion -> records only gen2 execution duration (~250ms, NOT 1 hour!)
        executionEventConsumer.consumeWorkflowCompleted(
                new WorkflowCompletedEvent(20L, "Retryable Workflow", true, 2L, "ev-2")
        );
        assertThat(registry.find("orynx.workflows.completed").tag("status", "COMPLETED").counter().count())
                .isEqualTo(1.0);
        Timer completedTimer = registry.find("orynx.workflows.duration").tag("status", "COMPLETED").timer();
        assertThat(completedTimer.count()).isEqualTo(1L);
        assertThat(completedTimer.totalTime(TimeUnit.SECONDS)).isLessThan(10.0);

        // 3. Cancel another workflow -> increments CANCELLED and is idempotent on repeat call
        Workflow cancelTarget = Workflow.builder()
                .id(30L)
                .name("Cancel Target")
                .status(WorkflowStatus.RUNNING)
                .userId(7L)
                .build();
        when(workflowRepository.findByIdAndUserIdForUpdate(30L, 7L)).thenReturn(Optional.of(cancelTarget));
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(30L))
                .thenReturn(Optional.empty());

        workflowService.cancelWorkflow(30L, 7L);
        workflowService.cancelWorkflow(30L, 7L); // Idempotent second call

        assertThat(registry.find("orynx.workflows.cancelled").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("orynx.workflows.completed").tag("status", "CANCELLED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void prometheusMetricsServer_bindsToLoopbackAndEnforcesMethodAndPrefixRules() throws Exception {
        metrics.recordWorkflowCreated();

        PrometheusMetricsServer server = new PrometheusMetricsServer(registry);
        ReflectionTestUtils.setField(server, "metricsPort", 0);
        ReflectionTestUtils.setField(server, "bindAddress", "127.0.0.1");
        server.start();
        try {
            com.sun.net.httpserver.HttpServer httpServer =
                    (com.sun.net.httpserver.HttpServer) ReflectionTestUtils.getField(server, "server");
            assertThat(httpServer.getAddress().getAddress().isLoopbackAddress()).isTrue();
            int port = httpServer.getAddress().getPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> getResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(getResponse.statusCode()).isEqualTo(200);
            assertThat(getResponse.body()).contains("orynx_workflows_total 1.0");

            HttpResponse<String> postResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(postResponse.statusCode()).isEqualTo(405);
        } finally {
            server.stop();
        }
    }
}
