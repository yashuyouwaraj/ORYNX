package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe registry managing active, scoped resilience faults.
 * Enables live application components to observe injected faults in a controlled test scope
 * without mutating shared Spring singleton configurations.
 */
@Component
public class ResilienceFaultRegistry {

    private final Map<ScenarioType, Map<String, String>> activeFaults = new ConcurrentHashMap<>();
    private final AtomicBoolean redisFaultActive = new AtomicBoolean(false);
    private volatile AdmissionFailurePolicy scopedRedisPolicy = null;

    public void activateFault(ScenarioType scenario, String targetResource, Map<String, String> parameters) {
        activeFaults.put(scenario, parameters != null ? parameters : Map.of());
        if (scenario == ScenarioType.REDIS_INTERRUPTION) {
            redisFaultActive.set(true);
            String policy = parameters != null ? parameters.get("policy") : null;
            if ("FAIL_OPEN".equalsIgnoreCase(policy)) {
                scopedRedisPolicy = AdmissionFailurePolicy.FAIL_OPEN;
            } else {
                scopedRedisPolicy = AdmissionFailurePolicy.FAIL_CLOSED;
            }
        }
    }

    public void deactivateFault(ScenarioType scenario) {
        activeFaults.remove(scenario);
        if (scenario == ScenarioType.REDIS_INTERRUPTION) {
            redisFaultActive.set(false);
            scopedRedisPolicy = null;
        }
    }

    public boolean isFaultActive(ScenarioType scenario) {
        return activeFaults.containsKey(scenario);
    }

    public boolean isRedisFaultActive() {
        return redisFaultActive.get();
    }

    public AdmissionFailurePolicy getScopedRedisPolicy(AdmissionFailurePolicy defaultPolicy) {
        AdmissionFailurePolicy current = scopedRedisPolicy;
        return current != null ? current : defaultPolicy;
    }

    public String getParameter(ScenarioType scenario, String key, String defaultValue) {
        Map<String, String> params = activeFaults.get(scenario);
        if (params == null) {
            return defaultValue;
        }
        return params.getOrDefault(key, defaultValue);
    }

    public void clearAll() {
        activeFaults.clear();
        redisFaultActive.set(false);
        scopedRedisPolicy = null;
    }
}
