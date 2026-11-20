package com.orynx.execution.worker;

import com.orynx.execution.execution.ActiveExecutionTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionWorkerPoolTest {

    private ThreadPoolTaskExecutor executor;
    private ExecutionWorkerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ExecutionWorkerProperties();
        properties.setCorePoolSize(2);
        properties.setMaxPoolSize(2);
        properties.setQueueCapacity(2);
        properties.setKeepAliveSeconds(10);
        properties.setAwaitTerminationSeconds(5);

        ExecutionWorkerPoolConfig config = new ExecutionWorkerPoolConfig(properties);
        executor = config.workflowExecutionExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void shouldConfigurePoolWithExpectedPropertiesAndThreadPrefix() throws Exception {
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isEqualTo(2);

        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        });

        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(threadName.get()).startsWith("workflow-worker-");
    }

    @Test
    void shouldExecuteTasksOnWorkerThreadSeparatedFromCallerThread() throws Exception {
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> workerThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            workerThread.set(Thread.currentThread().getName());
            latch.countDown();
        });

        latch.await(2, TimeUnit.SECONDS);
        assertThat(workerThread.get()).isNotEqualTo(callerThread);
        assertThat(workerThread.get()).startsWith("workflow-worker-");
    }

    @Test
    void shouldRejectTasksWhenQueueCapacityAndPoolAreExhausted() throws Exception {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(2);

        // Fill the 2 worker threads
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                startedLatch.countDown();
                try {
                    blockLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startedLatch.await(2, TimeUnit.SECONDS);

        // Fill the queue (capacity = 2)
        executor.submit(() -> {});
        executor.submit(() -> {});

        // 5th task must be rejected by AbortPolicy
        assertThatThrownBy(() -> executor.submit(() -> {}))
                .isInstanceOf(RejectedExecutionException.class);

        blockLatch.countDown();
    }

    @Test
    void shouldTrackAndInterruptActiveExecutionThreadViaTracker() throws Exception {
        ActiveExecutionTracker tracker = new ActiveExecutionTracker();
        Long workflowId = 101L;

        CountDownLatch threadStarted = new CountDownLatch(1);
        CountDownLatch threadInterrupted = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        Future<?> future = executor.submit(() -> {
            Thread currentThread = Thread.currentThread();
            tracker.register(workflowId, currentThread, null);
            threadStarted.countDown();

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                threadInterrupted.countDown();
                Thread.currentThread().interrupt();
            } finally {
                tracker.unregister(workflowId);
            }
        });

        threadStarted.await(2, TimeUnit.SECONDS);
        assertThat(tracker.getActiveCount()).isEqualTo(1);

        // Cancel via tracker
        tracker.cancel(workflowId);

        boolean interrupted = threadInterrupted.await(2, TimeUnit.SECONDS);
        assertThat(interrupted).isTrue();
        assertThat(wasInterrupted.get()).isTrue();
    }
}
