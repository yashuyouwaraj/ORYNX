package com.orynx.execution.handler;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic no-op task handler. Supports an optional bounded delayMs (0..5000ms)
 * for validating parallel branch overlap and cancellation without random failures.
 */
@Component
public class NoopTaskHandler implements TaskHandler {

    private static final long MAX_DELAY_MS = 5_000L;

    @Override
    public String getTaskType() {
        return "NOOP";
    }

    @Override
    public TaskHandlerResult execute(TaskHandlerContext context) throws InterruptedException {
        long delayMs = extractBoundedLong(context.getConfig(), "delayMs", 0L, MAX_DELAY_MS);
        if (delayMs > 0L) {
            Thread.sleep(delayMs);
        }
        if (context.isCancelled()) {
            return TaskHandlerResult.failed("Task cancelled");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("taskKey", context.getTaskKey());
        out.put("handler", "NOOP");
        return TaskHandlerResult.ok(out);
    }

    static long extractBoundedLong(Map<String, Object> config, String key, long min, long max) {
        if (config == null || !config.containsKey(key) || config.get(key) == null) {
            return min;
        }
        Object raw = config.get(key);
        long parsed;
        if (raw instanceof Number n) {
            parsed = n.longValue();
        } else {
            try {
                parsed = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                return min;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }
}
