package com.orynx.execution.execution;

import com.orynx.execution.execution.dto.TaskExecutionResult;
import com.orynx.execution.workflow.event.dto.TaskExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorTest {

    @Test
    void shouldSucceedOnFirstAttempt() {
        AtomicInteger invocations = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor(() -> {
            invocations.incrementAndGet();
            return true;
        }, 0L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .name("Collect Metrics")
                .executionOrder(1)
                .maxRetries(3)
                .build();

        TaskExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getTaskName()).isEqualTo("Collect Metrics");
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void shouldSucceedAfterRetry() {
        AtomicInteger invocations = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor(() -> invocations.incrementAndGet() == 3, 0L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .name("Analyze Logs")
                .executionOrder(2)
                .maxRetries(3)
                .build();

        TaskExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(3);
        assertThat(result.getTaskName()).isEqualTo("Analyze Logs");
        assertThat(invocations.get()).isEqualTo(3);
    }

    @Test
    void shouldSucceedOnFinalPermittedAttempt() {
        AtomicInteger invocations = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor(() -> invocations.incrementAndGet() == 3, 0L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .name("Validate Payload")
                .executionOrder(1)
                .maxRetries(2)
                .build();

        TaskExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(3);
        assertThat(invocations.get()).isEqualTo(3);
    }

    @Test
    void shouldFailAfterAllRetriesExhausted() {
        AtomicInteger invocations = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor(() -> {
            invocations.incrementAndGet();
            return false;
        }, 0L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .name("Generate Report")
                .executionOrder(3)
                .maxRetries(3)
                .build();

        TaskExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getAttempts()).isEqualTo(4);
        assertThat(result.getTaskName()).isEqualTo("Generate Report");
        assertThat(invocations.get()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void shouldNeverReportSuccessWhenFinalAttemptFails(int maxRetries) {
        AtomicInteger invocations = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor(() -> {
            invocations.incrementAndGet();
            return false;
        }, 0L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .name("Export Data")
                .executionOrder(1)
                .maxRetries(maxRetries)
                .build();

        TaskExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getAttempts()).isEqualTo(maxRetries + 1);
        assertThat(invocations.get()).isEqualTo(maxRetries + 1);
    }

    @Test
    void shouldRespectRetryBoundariesWhenMaxRetriesIsZero() {
        AtomicInteger failInvocations = new AtomicInteger();
        TaskExecutor failingExecutor = new TaskExecutor(() -> {
            failInvocations.incrementAndGet();
            return false;
        }, 0L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .name("Single Attempt Task")
                .executionOrder(1)
                .maxRetries(0)
                .build();

        TaskExecutionResult failResult = failingExecutor.execute(request);

        assertThat(failResult.isSuccess()).isFalse();
        assertThat(failResult.getAttempts()).isEqualTo(1);
        assertThat(failInvocations.get()).isEqualTo(1);

        AtomicInteger successInvocations = new AtomicInteger();
        TaskExecutor succeedingExecutor = new TaskExecutor(() -> {
            successInvocations.incrementAndGet();
            return true;
        }, 0L);

        TaskExecutionResult successResult = succeedingExecutor.execute(request);

        assertThat(successResult.isSuccess()).isTrue();
        assertThat(successResult.getAttempts()).isEqualTo(1);
        assertThat(successInvocations.get()).isEqualTo(1);
    }

    @Test
    void shouldNotExecuteBeyondMaxRetriesPlusOne() {
        AtomicInteger invocations = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor(() -> invocations.incrementAndGet() > 3, 0L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .name("Bounded Task")
                .executionOrder(1)
                .maxRetries(2)
                .build();

        TaskExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getAttempts()).isEqualTo(3);
        assertThat(invocations.get()).isEqualTo(3);
    }

    @Test
    void shouldTreatNullOrNegativeMaxRetriesAsZeroRetries() {
        AtomicInteger nullInvocations = new AtomicInteger();
        TaskExecutor nullExecutor = new TaskExecutor(() -> {
            nullInvocations.incrementAndGet();
            return false;
        }, 0L);

        TaskExecutionResult nullResult = nullExecutor.execute(TaskExecutionRequest.builder()
                .name("Null Retries Task")
                .executionOrder(1)
                .maxRetries(null)
                .build());

        assertThat(nullResult.isSuccess()).isFalse();
        assertThat(nullResult.getAttempts()).isEqualTo(1);
        assertThat(nullInvocations.get()).isEqualTo(1);

        AtomicInteger negativeInvocations = new AtomicInteger();
        TaskExecutor negativeExecutor = new TaskExecutor(() -> {
            negativeInvocations.incrementAndGet();
            return false;
        }, 0L);

        TaskExecutionResult negativeResult = negativeExecutor.execute(TaskExecutionRequest.builder()
                .name("Negative Retries Task")
                .executionOrder(1)
                .maxRetries(-2)
                .build());

        assertThat(negativeResult.isSuccess()).isFalse();
        assertThat(negativeResult.getAttempts()).isEqualTo(1);
        assertThat(negativeInvocations.get()).isEqualTo(1);
    }
}
