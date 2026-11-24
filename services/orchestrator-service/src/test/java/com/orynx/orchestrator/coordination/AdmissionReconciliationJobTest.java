package com.orynx.orchestrator.coordination;

import com.orynx.orchestrator.coordination.admission.AdmissionReconciliationJob;
import com.orynx.orchestrator.coordination.admission.RedisWorkflowAdmissionController;
import com.orynx.orchestrator.coordination.admission.WorkflowAdmissionController;
import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.lock.DistributedLockService;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdmissionReconciliationJobTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private WorkflowAdmissionController admissionController;
    @Mock
    private DistributedLockService distributedLockService;
    @Mock
    private WorkflowRepository workflowRepository;

    private RedisCoordinationProperties properties;
    private RedisCoordinationMetrics metrics;
    private AdmissionReconciliationJob reconciliationJob;

    @BeforeEach
    void setUp() {
        properties = new RedisCoordinationProperties();
        properties.setEnabled(true);
        properties.setReconciliationIntervalSeconds(60);
        properties.setInflightGracePeriodSeconds(60);
        properties.setMaxGlobalConcurrentWorkflows(10);
        properties.setWorkflowLeaseTtlSeconds(3600);

        metrics = new RedisCoordinationMetrics(new SimpleMeterRegistry());

        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // By default, executeWithLock executes the supplier
        lenient().when(distributedLockService.executeWithLock(anyString(), any(Duration.class), any()))
                .thenAnswer(inv -> {
                    Supplier<?> action = inv.getArgument(2);
                    return Optional.ofNullable(action.get());
                });

        reconciliationJob = new AdmissionReconciliationJob(
                redisTemplate,
                admissionController,
                distributedLockService,
                workflowRepository,
                properties,
                metrics
        );
    }

    @Test
    @DisplayName("reconcile skips when leader lock cannot be acquired by this instance")
    void reconcileSkippedWhenLockCannotBeAcquired() {
        when(distributedLockService.executeWithLock(eq("admission-reconciliation-leader"), any(Duration.class), any()))
                .thenReturn(Optional.empty());

        reconciliationJob.reconcile();

        verifyNoInteractions(workflowRepository);
        verifyNoInteractions(admissionController);
    }

    @Test
    @DisplayName("reconcile protects in-flight CREATED workflow within grace period")
    void reconcileProtectsInflightCreatedWorkflowWithinGracePeriod() {
        when(redisTemplate.hasKey(RedisWorkflowAdmissionController.INITIALIZED_KEY)).thenReturn(true);
        when(setOperations.members(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY)).thenReturn(Set.of("101"));

        Workflow wf = Workflow.builder()
                .id(101L)
                .userId(1L)
                .status(WorkflowStatus.CREATED)
                .build();
        when(workflowRepository.findById(101L)).thenReturn(Optional.of(wf));

        // Lease timestamp is fresh: admitted 10 seconds ago
        long admittedAtMs = System.currentTimeMillis() - 10_000L;
        String leaseValue = "1:uuid-token:" + admittedAtMs;
        when(valueOperations.get(RedisWorkflowAdmissionController.LEASE_PREFIX + "101")).thenReturn(leaseValue);

        reconciliationJob.reconcile();

        // Release should NOT be called for the in-flight workflow
        verify(admissionController, never()).release(eq(101L), any());
    }

    @Test
    @DisplayName("reconcile purges abandoned CREATED workflow exceeding grace period")
    void reconcilePurgesCreatedWorkflowExceedingGracePeriod() {
        when(redisTemplate.hasKey(RedisWorkflowAdmissionController.INITIALIZED_KEY)).thenReturn(true);
        when(setOperations.members(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY)).thenReturn(Set.of("102"));

        Workflow wf = Workflow.builder()
                .id(102L)
                .userId(2L)
                .status(WorkflowStatus.CREATED)
                .build();
        when(workflowRepository.findById(102L)).thenReturn(Optional.of(wf));

        // Lease timestamp is old: admitted 90 seconds ago (grace period is 60s)
        long admittedAtMs = System.currentTimeMillis() - 90_000L;
        String leaseValue = "2:uuid-token:" + admittedAtMs;
        when(valueOperations.get(RedisWorkflowAdmissionController.LEASE_PREFIX + "102")).thenReturn(leaseValue);

        reconciliationJob.reconcile();

        // Release SHOULD be called to clean up the abandoned slot
        verify(admissionController, times(1)).release(102L, 2L);
    }

    @Test
    @DisplayName("reconcile purges terminal and paused workflows from active set")
    void reconcilePurgesTerminalWorkflows() {
        when(redisTemplate.hasKey(RedisWorkflowAdmissionController.INITIALIZED_KEY)).thenReturn(true);
        when(setOperations.members(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY))
                .thenReturn(Set.of("201", "202", "203", "204"));

        Workflow completedWf = Workflow.builder().id(201L).userId(1L).status(WorkflowStatus.COMPLETED).build();
        Workflow failedWf = Workflow.builder().id(202L).userId(2L).status(WorkflowStatus.FAILED).build();
        Workflow cancelledWf = Workflow.builder().id(203L).userId(3L).status(WorkflowStatus.CANCELLED).build();
        Workflow pausedWf = Workflow.builder().id(204L).userId(4L).status(WorkflowStatus.PAUSED).build();

        when(workflowRepository.findById(201L)).thenReturn(Optional.of(completedWf));
        when(workflowRepository.findById(202L)).thenReturn(Optional.of(failedWf));
        when(workflowRepository.findById(203L)).thenReturn(Optional.of(cancelledWf));
        when(workflowRepository.findById(204L)).thenReturn(Optional.of(pausedWf));

        reconciliationJob.reconcile();

        verify(admissionController, times(1)).release(201L, 1L);
        verify(admissionController, times(1)).release(202L, 2L);
        verify(admissionController, times(1)).release(203L, 3L);
        verify(admissionController, times(1)).release(204L, 4L);
    }

    @Test
    @DisplayName("reconcile retains genuinely RUNNING workflows")
    void reconcileRetainsRunningWorkflows() {
        when(redisTemplate.hasKey(RedisWorkflowAdmissionController.INITIALIZED_KEY)).thenReturn(true);
        when(setOperations.members(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY)).thenReturn(Set.of("301"));

        Workflow runningWf = Workflow.builder().id(301L).userId(1L).status(WorkflowStatus.RUNNING).build();
        when(workflowRepository.findById(301L)).thenReturn(Optional.of(runningWf));

        reconciliationJob.reconcile();

        verify(admissionController, never()).release(anyLong(), any());
    }

    @Test
    @DisplayName("cold-restart recovery reconstructs Redis admission state from PostgreSQL RUNNING workflows")
    void coldRestartRecoveryReconstructsStateFromDatabase() {
        // Initialized key is missing in Redis
        when(redisTemplate.hasKey(RedisWorkflowAdmissionController.INITIALIZED_KEY))
                .thenReturn(false)
                .thenReturn(false);

        Workflow running1 = Workflow.builder().id(401L).userId(10L).status(WorkflowStatus.RUNNING).startedAt(System.currentTimeMillis()).build();
        Workflow running2 = Workflow.builder().id(402L).userId(20L).status(WorkflowStatus.RUNNING).startedAt(System.currentTimeMillis()).build();

        when(workflowRepository.findByStatusOrderByIdDesc(WorkflowStatus.RUNNING))
                .thenReturn(List.of(running1, running2));

        reconciliationJob.recoverIfUninitialized();

        // Verify active sets and lease keys are populated
        verify(setOperations).add(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY, "401");
        verify(setOperations).add(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY, "402");
        verify(setOperations).add(RedisWorkflowAdmissionController.USER_WORKFLOWS_PREFIX + 10L, "401");
        verify(setOperations).add(RedisWorkflowAdmissionController.USER_WORKFLOWS_PREFIX + 20L, "402");

        verify(valueOperations).set(eq(RedisWorkflowAdmissionController.LEASE_PREFIX + "401"), contains("10:"), eq(Duration.ofSeconds(3600)));
        verify(valueOperations).set(eq(RedisWorkflowAdmissionController.LEASE_PREFIX + "402"), contains("20:"), eq(Duration.ofSeconds(3600)));

        // Verify initialized marker key is set with 24h expiration
        verify(valueOperations).set(eq(RedisWorkflowAdmissionController.INITIALIZED_KEY), eq("true"), eq(Duration.ofHours(24)));
    }
}
