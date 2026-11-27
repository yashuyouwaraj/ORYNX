package com.orynx.execution.handler;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of pluggable deterministic and opt-in simulated task handlers.
 */
@Component
public class TaskHandlerRegistry {

    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    public TaskHandlerRegistry(List<TaskHandler> handlerList) {
        if (handlerList != null) {
            for (TaskHandler handler : handlerList) {
                if (handler != null && handler.getTaskType() != null) {
                    handlers.put(handler.getTaskType().trim().toUpperCase(Locale.ROOT), handler);
                }
            }
        }
    }

    public TaskHandler resolve(String taskType) {
        String normalized = (taskType == null || taskType.isBlank())
                ? "NOOP"
                : taskType.trim().toUpperCase(Locale.ROOT);
        TaskHandler handler = handlers.get(normalized);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported taskType: '" + normalized + "'");
        }
        return handler;
    }

    public Set<String> registeredTypes() {
        return Set.copyOf(handlers.keySet());
    }
}
