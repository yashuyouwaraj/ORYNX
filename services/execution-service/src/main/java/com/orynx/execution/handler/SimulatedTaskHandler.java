package com.orynx.execution.handler;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Explicitly opt-in simulated task handler for resilience, retry, timeout, and failure-propagation testing.
 * Configurable failureProbability [0.0..1.0] (default 0.0) and delayMs [0..10000] (default 0).
 */
@Component
public class SimulatedTaskHandler implements TaskHandler {

    private static final long MAX_DELAY_MS = 10_000L;

    @Override
    public String getTaskType() {
        return "SIMULATED";
    }

    @Override
    public TaskHandlerResult execute(TaskHandlerContext context) throws InterruptedException {
        Map<String, Object> cfg = context.getConfig() != null ? context.getConfig() : Map.of();
        long delayMs = NoopTaskHandler.extractBoundedLong(cfg, "delayMs", 0L, MAX_DELAY_MS);
        double failureProb = extractBoundedDouble(cfg, "failureProbability", 0.0, 1.0);
        boolean forceFail = Boolean.parseBoolean(String.valueOf(cfg.getOrDefault("forceFail", "false")));
        long failUntilAttempt = NoopTaskHandler.extractBoundedLong(cfg, "failUntilAttempt", 0L, 10L);

        if (delayMs > 0L) {
            Thread.sleep(delayMs);
        }
        if (context.isCancelled()) {
            return TaskHandlerResult.failed("Simulated task cancelled");
        }
        if (forceFail || context.getAttempt() <= failUntilAttempt) {
            return TaskHandlerResult.failed("Simulated deterministic failure on attempt " + context.getAttempt());
        }
        if (failureProb > 0.0 && ThreadLocalRandom.current().nextDouble() < failureProb) {
            return TaskHandlerResult.failed("Simulated stochastic failure (p=" + failureProb + ")");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "SIMULATED_OK");
        out.put("taskKey", context.getTaskKey());
        out.put("attempt", context.getAttempt());
        return TaskHandlerResult.ok(out);
    }

    private double extractBoundedDouble(Map<String, Object> config, String key, double min, double max) {
        if (config == null || !config.containsKey(key) || config.get(key) == null) {
            return min;
        }
        Object raw = config.get(key);
        double parsed;
        if (raw instanceof Number n) {
            parsed = n.doubleValue();
        } else {
            try {
                parsed = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                return min;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }
}
