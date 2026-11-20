package com.orynx.execution.execution;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
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
        private final Long generation;
        private final Future<?> future;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean fenced = new AtomicBoolean(false);

        public ActiveExecutionHandle(Long workflowId, Thread workerThread, Long generation, Future<?> future) {
            this.workflowId = workflowId;
            this.workerThread = workerThread;
            this.generation = generation != null ? generation : 1L;
            this.future = future;
        }

        public void cancel() {
            cancelled.set(true);
            interruptThread("cancelled");
        }

        public void pause() {
            paused.set(true);
        }

        public void fence() {
            fenced.set(true);
            interruptThread("fenced/stale");
        }

        private void interruptThread(String reason) {
            if (workerThread != null && workerThread.isAlive()) {
                log.info("Interrupting worker thread {} for {} workflow {}", workerThread.getName(), reason, workflowId);
                workerThread.interrupt();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isPaused() {
            return paused.get();
        }

        public boolean isFenced() {
            return fenced.get();
        }
    }

    private final ConcurrentHashMap<Long, ActiveExecutionHandle> activeExecutions = new ConcurrentHashMap<>();

    public void register(Long workflowId, Thread thread, Long generation, Future<?> future) {
        ActiveExecutionHandle handle = new ActiveExecutionHandle(workflowId, thread, generation, future);
        activeExecutions.put(workflowId, handle);
        log.debug("Registered active execution for workflow {} (gen={}) on thread {}",
                workflowId, generation, thread != null ? thread.getName() : "n/a");
    }

    public void register(Long workflowId, Thread thread, Future<?> future) {
        register(workflowId, thread, 1L, future);
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

    public void fence(Long workflowId) {
        ActiveExecutionHandle handle = activeExecutions.get(workflowId);
        if (handle != null) {
            handle.fence();
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

    public boolean isFenced(Long workflowId, Long generation) {
        ActiveExecutionHandle handle = activeExecutions.get(workflowId);
        if (handle == null) {
            return false;
        }
        if (handle.isFenced()) {
            return true;
        }
        return generation != null && handle.getGeneration() != null && !generation.equals(handle.getGeneration());
    }

    public boolean isLocallyActive(Long workflowId) {
        return activeExecutions.containsKey(workflowId);
    }

    public int getActiveCount() {
        return activeExecutions.size();
    }

    public Collection<ActiveExecutionHandle> getActiveHandles() {
        return Collections.unmodifiableCollection(activeExecutions.values());
    }
}
