package com.orynx.execution.execution;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorkflowCancellationRegistry {
    private final Set<Long> cancelledWorkflows = ConcurrentHashMap.newKeySet();

    public void cancel(Long workflowId){
        cancelledWorkflows.add(workflowId);
    }

    public boolean isCancelled(Long workflowId){
        return cancelledWorkflows.contains(workflowId);
    }

    public void clear(Long workflowId){
        cancelledWorkflows.remove(workflowId);
    }
}
