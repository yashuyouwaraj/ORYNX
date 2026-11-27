package com.orynx.orchestrator.workflow.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowTaskRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded Directed Acyclic Graph (DAG) validator using Kahn's algorithm.
 * Enforces cycle freedom, key uniqueness, dependency existence, depth/node/edge bounds,
 * and backward-compatible linear-to-DAG normalization when explicit dependencies are omitted.
 */
@Component
public class DagValidator {

    public static final int MAX_NODES = 100;
    public static final int MAX_EDGES = 500;
    public static final int MAX_DEPTH = 32;
    public static final long MIN_TIMEOUT_MS = 100L;
    public static final long MAX_TIMEOUT_MS = 300_000L;
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    public static final int MAX_RETRIES_LIMIT = 10;
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final int MAX_CONFIG_BYTES = 16_384;
    public static final int MAX_TOTAL_WORKFLOW_CONFIG_BYTES = 262_144;
    public static final int MAX_CONFIG_NESTING_DEPTH = 8;
    public static final int MAX_CONFIG_KEYS = 64;

    public static final Set<String> ALLOWED_TASK_TYPES = Set.of("NOOP", "ECHO", "TRANSFORM", "SIMULATED");
    private static final Pattern VALID_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final ObjectMapper CONFIG_SIZE_MAPPER = new ObjectMapper();

    public record ValidatedTaskSpec(
            String taskKey,
            String name,
            String taskType,
            List<String> dependsOn,
            Map<String, Object> config,
            long timeoutMs,
            int maxRetries,
            int executionOrder,
            int depth
    ) {}

    public record ValidatedDag(
            List<ValidatedTaskSpec> topologicalTasks,
            int edgeCount,
            int maxDepth
    ) {}

    public ValidatedDag validateAndNormalize(List<CreateWorkflowTaskRequest> rawTasks) {
        if (rawTasks == null || rawTasks.isEmpty()) {
            throw new IllegalArgumentException("Workflow DAG must contain at least one task");
        }
        if (rawTasks.size() > MAX_NODES) {
            throw new IllegalArgumentException("Workflow DAG exceeds maximum node limit of " + MAX_NODES);
        }

        // Determine whether the caller provided any explicit dependency declarations
        boolean hasExplicitDagEdges = rawTasks.stream()
                .anyMatch(t -> t != null && t.getDependsOn() != null);

        List<CreateWorkflowTaskRequest> orderedInput = new ArrayList<>(rawTasks);
        if (!hasExplicitDagEdges) {
            // Preserve legacy executionOrder sorting when no explicit DAG edges were specified
            boolean hasOrder = orderedInput.stream().anyMatch(t -> t != null && t.getExecutionOrder() != null);
            if (hasOrder) {
                orderedInput.sort(Comparator.comparingInt(
                        t -> t.getExecutionOrder() != null ? t.getExecutionOrder() : Integer.MAX_VALUE
                ));
            }
        }

        Map<String, CreateWorkflowTaskRequest> byKey = new LinkedHashMap<>();
        List<String> normalizedKeys = new ArrayList<>(orderedInput.size());
        int totalConfigBytes = 0;

        for (int i = 0; i < orderedInput.size(); i++) {
            CreateWorkflowTaskRequest req = orderedInput.get(i);
            if (req == null) {
                throw new IllegalArgumentException("Workflow task entry at index " + i + " must not be null");
            }
            if (req.getName() == null || req.getName().isBlank()) {
                throw new IllegalArgumentException("Workflow task at index " + i + " must have a non-blank name");
            }

            String key = (req.getTaskKey() != null && !req.getTaskKey().isBlank())
                    ? req.getTaskKey().trim()
                    : "task-" + (i + 1);

            if (!VALID_KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid taskKey '" + key + "': must match ^[A-Za-z0-9._-]{1,64}$");
            }
            if (byKey.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate taskKey detected in workflow DAG: '" + key + "'");
            }

            String taskType = normalizeTaskType(req.getTaskType());
            validateBounds(key, req);
            totalConfigBytes += validateConfigPayload(key, req.getConfig());
            if (totalConfigBytes > MAX_TOTAL_WORKFLOW_CONFIG_BYTES) {
                throw new IllegalArgumentException(
                        "Workflow DAG total config payload exceeds maximum limit of " + MAX_TOTAL_WORKFLOW_CONFIG_BYTES + " bytes"
                );
            }

            byKey.put(key, req);
            normalizedKeys.add(key);
        }

        // Build adjacency list and in-degree map
        Map<String, List<String>> depsByTask = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (String key : normalizedKeys) {
            dependents.put(key, new ArrayList<>());
            inDegree.put(key, 0);
        }

        int totalEdges = 0;
        for (int i = 0; i < normalizedKeys.size(); i++) {
            String key = normalizedKeys.get(i);
            CreateWorkflowTaskRequest req = byKey.get(key);
            List<String> deps;

            if (hasExplicitDagEdges) {
                List<String> rawDeps = req.getDependsOn() != null ? req.getDependsOn() : List.of();
                Set<String> deduped = new LinkedHashSet<>();
                for (String rawDep : rawDeps) {
                    if (rawDep == null || rawDep.isBlank()) {
                        throw new IllegalArgumentException("Task '" + key + "' contains a blank dependency key");
                    }
                    String dep = rawDep.trim();
                    if (dep.equals(key)) {
                        throw new IllegalArgumentException("Self-dependency detected on task '" + key + "'");
                    }
                    if (!byKey.containsKey(dep)) {
                        throw new IllegalArgumentException("Task '" + key + "' depends on nonexistent taskKey '" + dep + "'");
                    }
                    deduped.add(dep);
                }
                deps = List.copyOf(deduped);
            } else {
                // Legacy linear chain mapping: task[i] depends on task[i - 1]
                deps = (i == 0) ? List.of() : List.of(normalizedKeys.get(i - 1));
            }

            totalEdges += deps.size();
            if (totalEdges > MAX_EDGES) {
                throw new IllegalArgumentException("Workflow DAG exceeds maximum dependency edge limit of " + MAX_EDGES);
            }

            depsByTask.put(key, deps);
            inDegree.put(key, deps.size());
            for (String dep : deps) {
                dependents.get(dep).add(key);
            }
        }

        // Kahn's topological sort + longest-path depth calculation (deterministic queue order)
        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String, Integer> depthMap = new HashMap<>();
        for (String key : normalizedKeys) {
            if (inDegree.get(key) == 0) {
                queue.add(key);
                depthMap.put(key, 1);
            }
        }

        List<String> topoOrder = new ArrayList<>(normalizedKeys.size());
        int maxDepth = 0;

        while (!queue.isEmpty()) {
            String current = queue.poll();
            topoOrder.add(current);
            int curDepth = depthMap.getOrDefault(current, 1);
            if (curDepth > maxDepth) {
                maxDepth = curDepth;
            }
            if (curDepth > MAX_DEPTH) {
                throw new IllegalArgumentException("Workflow DAG exceeds maximum depth of " + MAX_DEPTH);
            }

            for (String next : dependents.get(current)) {
                int candidateDepth = curDepth + 1;
                depthMap.merge(next, candidateDepth, Math::max);
                int remaining = inDegree.get(next) - 1;
                inDegree.put(next, remaining);
                if (remaining == 0) {
                    queue.add(next);
                }
            }
        }

        if (topoOrder.size() != normalizedKeys.size()) {
            throw new IllegalArgumentException("Cycle detected in workflow DAG: graph must be acyclic");
        }

        List<ValidatedTaskSpec> validated = new ArrayList<>(topoOrder.size());
        for (int i = 0; i < topoOrder.size(); i++) {
            String key = topoOrder.get(i);
            CreateWorkflowTaskRequest req = byKey.get(key);
            long timeoutMs = req.getTimeoutMs() != null ? req.getTimeoutMs() : DEFAULT_TIMEOUT_MS;
            int maxRetries = req.getMaxRetries() != null ? req.getMaxRetries() : DEFAULT_MAX_RETRIES;
            Map<String, Object> config = req.getConfig() != null ? Map.copyOf(req.getConfig()) : Map.of();

            validated.add(new ValidatedTaskSpec(
                    key,
                    req.getName().trim(),
                    normalizeTaskType(req.getTaskType()),
                    depsByTask.get(key),
                    config,
                    timeoutMs,
                    maxRetries,
                    i + 1,
                    depthMap.getOrDefault(key, 1)
            ));
        }

        return new ValidatedDag(List.copyOf(validated), totalEdges, maxDepth);
    }

    private String normalizeTaskType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "NOOP";
        }
        String upper = rawType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TASK_TYPES.contains(upper)) {
            throw new IllegalArgumentException(
                    "Unsupported taskType '" + rawType + "'. Allowed types: " + ALLOWED_TASK_TYPES
            );
        }
        return upper;
    }

    private void validateBounds(String key, CreateWorkflowTaskRequest req) {
        if (req.getTimeoutMs() != null) {
            if (req.getTimeoutMs() < MIN_TIMEOUT_MS || req.getTimeoutMs() > MAX_TIMEOUT_MS) {
                throw new IllegalArgumentException(
                        "Task '" + key + "' timeoutMs must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS
                );
            }
        }
        if (req.getMaxRetries() != null) {
            if (req.getMaxRetries() < 0 || req.getMaxRetries() > MAX_RETRIES_LIMIT) {
                throw new IllegalArgumentException(
                        "Task '" + key + "' maxRetries must be between 0 and " + MAX_RETRIES_LIMIT
                );
            }
        }
    }

    private int validateConfigPayload(String key, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return 0;
        }
        if (config.size() > MAX_CONFIG_KEYS) {
            throw new IllegalArgumentException(
                    "Task '" + key + "' config exceeds maximum top-level key limit of " + MAX_CONFIG_KEYS
            );
        }
        validateNestingDepth(key, config, 1);
        try {
            byte[] serialized = CONFIG_SIZE_MAPPER.writeValueAsString(config).getBytes(StandardCharsets.UTF_8);
            if (serialized.length > MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException(
                        "Task '" + key + "' config exceeds maximum serialized size of " + MAX_CONFIG_BYTES + " bytes"
                );
            }
            return serialized.length;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Task '" + key + "' config could not be serialized");
        }
    }

    private void validateNestingDepth(String key, Object value, int depth) {
        if (depth > MAX_CONFIG_NESTING_DEPTH) {
            throw new IllegalArgumentException(
                    "Task '" + key + "' config exceeds maximum nesting depth of " + MAX_CONFIG_NESTING_DEPTH
            );
        }
        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                validateNestingDepth(key, v, depth + 1);
            }
        } else if (value instanceof Collection<?> col) {
            for (Object item : col) {
                validateNestingDepth(key, item, depth + 1);
            }
        }
    }
}
