package com.orynx.execution.handler;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Narrowly scoped, deterministic data transformation handler.
 * Supports only fixed, safe operations (UPPERCASE, LOWERCASE, CONCAT, PICK_KEYS, MERGE_UPSTREAM)
 * with zero scripting, expression evaluation, or shell execution.
 */
@Component
public class TransformTaskHandler implements TaskHandler {

    @Override
    public String getTaskType() {
        return "TRANSFORM";
    }

    @Override
    public TaskHandlerResult execute(TaskHandlerContext context) {
        Map<String, Object> cfg = context.getConfig() != null ? context.getConfig() : Map.of();
        String op = String.valueOf(cfg.getOrDefault("operation", "MERGE_UPSTREAM"))
                .trim()
                .toUpperCase(Locale.ROOT);

        Map<String, Object> out = new LinkedHashMap<>();
        switch (op) {
            case "UPPERCASE" -> {
                String value = String.valueOf(cfg.getOrDefault("value", ""));
                out.put("result", value.toUpperCase(Locale.ROOT));
            }
            case "LOWERCASE" -> {
                String value = String.valueOf(cfg.getOrDefault("value", ""));
                out.put("result", value.toLowerCase(Locale.ROOT));
            }
            case "CONCAT" -> {
                String delimiter = String.valueOf(cfg.getOrDefault("delimiter", ","));
                Object rawParts = cfg.get("parts");
                if (rawParts instanceof List<?> parts) {
                    String joined = parts.stream().map(String::valueOf).reduce((a, b) -> a + delimiter + b).orElse("");
                    out.put("result", joined);
                } else {
                    out.put("result", "");
                }
            }
            case "PICK_KEYS" -> {
                Object rawKeys = cfg.get("keys");
                Map<String, Object> merged = mergeUpstream(context.getUpstreamOutputs());
                if (rawKeys instanceof List<?> keys) {
                    for (Object k : keys) {
                        String key = String.valueOf(k);
                        if (merged.containsKey(key)) {
                            out.put(key, merged.get(key));
                        }
                    }
                }
            }
            case "MERGE_UPSTREAM" -> out.putAll(mergeUpstream(context.getUpstreamOutputs()));
            default -> {
                return TaskHandlerResult.failed("Unsupported TRANSFORM operation: " + op);
            }
        }
        return TaskHandlerResult.ok(out);
    }

    private Map<String, Object> mergeUpstream(Map<String, Map<String, Object>> upstream) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (upstream != null) {
            for (Map.Entry<String, Map<String, Object>> entry : upstream.entrySet()) {
                if (entry.getValue() != null) {
                    merged.putAll(entry.getValue());
                }
            }
        }
        return merged;
    }
}
