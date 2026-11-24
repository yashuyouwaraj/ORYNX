package com.orynx.orchestrator.resilience.engine;

import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ExperimentResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe in-memory registry for resilience experiment definitions and execution results.
 * Bounds history to the most recent entries to prevent memory accumulation.
 */
@Component
public class ExperimentRegistry {

    private static final int MAX_HISTORY_SIZE = 100;

    private final Map<String, ExperimentDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, ExperimentResult> results = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> executionHistory = new ConcurrentLinkedDeque<>();

    public void register(ExperimentDefinition definition) {
        if (definition != null && definition.getId() != null) {
            definitions.put(definition.getId(), definition);
        }
    }

    public void recordResult(ExperimentResult result) {
        if (result != null && result.getExperimentId() != null) {
            results.put(result.getExperimentId(), result);
            executionHistory.addFirst(result.getExperimentId());

            while (executionHistory.size() > MAX_HISTORY_SIZE) {
                String oldest = executionHistory.pollLast();
                if (oldest != null) {
                    results.remove(oldest);
                    definitions.remove(oldest);
                }
            }
        }
    }

    public Optional<ExperimentDefinition> getDefinition(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Optional<ExperimentResult> getResult(String id) {
        return Optional.ofNullable(results.get(id));
    }

    public List<ExperimentResult> getRecentResults() {
        List<ExperimentResult> list = new ArrayList<>();
        for (String id : executionHistory) {
            ExperimentResult res = results.get(id);
            if (res != null) {
                list.add(res);
            }
        }
        return list;
    }

    public void clear() {
        definitions.clear();
        results.clear();
        executionHistory.clear();
    }
}
