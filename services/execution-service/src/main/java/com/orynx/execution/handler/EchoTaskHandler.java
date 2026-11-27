package com.orynx.execution.handler;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic ECHO handler returning validated task config and upstream outputs.
 */
@Component
public class EchoTaskHandler implements TaskHandler {

    @Override
    public String getTaskType() {
        return "ECHO";
    }

    @Override
    public TaskHandlerResult execute(TaskHandlerContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskKey", context.getTaskKey());
        if (context.getConfig() != null && !context.getConfig().isEmpty()) {
            out.put("input", context.getConfig());
        }
        if (context.getUpstreamOutputs() != null && !context.getUpstreamOutputs().isEmpty()) {
            out.put("upstream", context.getUpstreamOutputs());
        }
        return TaskHandlerResult.ok(out);
    }
}
