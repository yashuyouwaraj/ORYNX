package com.orynx.execution.execution;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class ActiveExecutionTracker {

    @Getter
    public static class ActiveExecutionHandle {
        private final Long workflowId;
        private final Thread workerThread;
        private final Future<?> future;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean paused = new AtomicBoolean(false);

        public ActiveExecutionHandle(Long workflowId, Thread workerThread, Future<?> future) {
            this.workflowId = workflowId;
            this.workerThread = workerThread;
            this.future = future;
        }

        public void cancel() {
            cancelled.set(true);
            if (workerThread != null && workerThread.isAlive()) {
                log.info("Interrupting worker thread {} for cancelled workflow {}", workerThread.getName(), workflowId);
                workerThread.interrupt();
            }
        }

        public void pause() {
            paused.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isPaused() {
            return paused.get();
        }
    }

    private final ConcurrentHashMap<Long, ActiveExecutionHandle> activeExecutions = new ConcurrentHashMap<>();

    public void register(Long workflowId, Thread thread, Future<?> future) {
        ActiveExecutionHandle handle = new ActiveExecutionHandle(workflowId, thread, future);
        activeExecutions.put(workflowId, handle);
        log.debug("Registered active execution for workflow {} on thread {}", workflowId, thread != null ? thread.getName() : "n/a");
    }

    public void unregister(Long workflowId) {
        activeExecutions.remove(workflowId);
        log.debug("Unregistered active execution for workflow {}", workflowId);
    }

    public void cancel(Long workflowId) {
        ActiveExecutionHandle handle = activeExecutions.get(workflowId);
        if (handle != null) {
            handle.cancel();
        }
    }

    public void pause(Long workflowId) {
        ActiveExecutionHandle handle = activeExecutions.get(workflowId);
        if (handle != null) {
            handle.pause();
        }
    }

    public boolean isCancelled(Long workflowId) {
        ActiveExecutionHandle handle = activeExecutions.get(workflowId);
        return handle != null && handle.isCancelled();
    }

    public boolean isPaused(Long workflowId) {
        ActiveExecutionHandle handle = activeExecutions.get(workflowId);
        return handle != null && handle.isPaused();
    }

    public int getActiveCount() {
        return activeExecutions.size();
    }
}
