package com.orynx.execution.execution;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorkflowPauseRegistry {

    private final Set<Long> pausedWorkflows =
            ConcurrentHashMap.newKeySet();

    public void pause(Long workflowId) {
        pausedWorkflows.add(workflowId);
    }

    public boolean isPaused(Long workflowId) {
        return pausedWorkflows.contains(workflowId);
    }

    public void clear(Long workflowId) {
        pausedWorkflows.remove(workflowId);
    }
}