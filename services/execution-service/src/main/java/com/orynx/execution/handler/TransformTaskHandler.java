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

    public static final int DEFAULT_MAX_PARTS = 256;
    public static final int DEFAULT_MAX_STRING_CHARS = 16_384;
    public static final int DEFAULT_MAX_MERGED_KEYS = 256;

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
                if (value.length() > DEFAULT_MAX_STRING_CHARS) {
                    return TaskHandlerResult.failed("TRANSFORM UPPERCASE input exceeds maximum length of " + DEFAULT_MAX_STRING_CHARS);
                }
                out.put("result", value.toUpperCase(Locale.ROOT));
            }
            case "LOWERCASE" -> {
                String value = String.valueOf(cfg.getOrDefault("value", ""));
                if (value.length() > DEFAULT_MAX_STRING_CHARS) {
                    return TaskHandlerResult.failed("TRANSFORM LOWERCASE input exceeds maximum length of " + DEFAULT_MAX_STRING_CHARS);
                }
                out.put("result", value.toLowerCase(Locale.ROOT));
            }
            case "CONCAT" -> {
                String delimiter = String.valueOf(cfg.getOrDefault("delimiter", ","));
                if (delimiter.length() > 64) {
                    return TaskHandlerResult.failed("TRANSFORM CONCAT delimiter exceeds maximum length of 64");
                }
                Object rawParts = cfg.get("parts");
                if (rawParts instanceof List<?> parts) {
                    if (parts.size() > DEFAULT_MAX_PARTS) {
                        return TaskHandlerResult.failed("TRANSFORM CONCAT exceeds maximum parts limit of " + DEFAULT_MAX_PARTS);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) {
                        if (i > 0) {
                            sb.append(delimiter);
                        }
                        sb.append(parts.get(i));
                        if (sb.length() > DEFAULT_MAX_STRING_CHARS) {
                            return TaskHandlerResult.failed("TRANSFORM CONCAT output exceeds maximum length of " + DEFAULT_MAX_STRING_CHARS);
                        }
                    }
                    out.put("result", sb.toString());
                } else {
                    out.put("result", "");
                }
            }
            case "PICK_KEYS" -> {
                Object rawKeys = cfg.get("keys");
                Map<String, Object> merged = mergeUpstream(context.getUpstreamOutputs());
                if (rawKeys instanceof List<?> keys) {
                    if (keys.size() > DEFAULT_MAX_MERGED_KEYS) {
                        return TaskHandlerResult.failed("TRANSFORM PICK_KEYS exceeds maximum key limit of " + DEFAULT_MAX_MERGED_KEYS);
                    }
                    for (Object k : keys) {
                        String key = String.valueOf(k);
                        if (merged.containsKey(key)) {
                            out.put(key, merged.get(key));
                        }
                    }
                }
            }
            case "MERGE_UPSTREAM" -> {
                Map<String, Object> merged = mergeUpstream(context.getUpstreamOutputs());
                if (merged.size() > DEFAULT_MAX_MERGED_KEYS) {
                    return TaskHandlerResult.failed("TRANSFORM MERGE_UPSTREAM exceeds maximum key limit of " + DEFAULT_MAX_MERGED_KEYS);
                }
                out.putAll(merged);
            }
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
