package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe registry managing active, scoped resilience faults with experiment ownership tracking.
 * Enables live application components to observe injected faults in a controlled test scope
 * without mutating shared Spring singleton configurations.
 *
 * Scopes faults to the activating experimentId so an abandoned/stale experiment
 * cannot accidentally deactivate a fault belonging to a newer experiment.
 */
@Component
public class ResilienceFaultRegistry {

    public static class ActiveFault {
        private final String experimentId;
        private final ScenarioType scenario;
        private final String targetResource;
        private final Map<String, String> parameters;
        private final long activatedAt;

        public ActiveFault(String experimentId, ScenarioType scenario, String targetResource, Map<String, String> parameters) {
            this.experimentId = experimentId;
            this.scenario = scenario;
            this.targetResource = targetResource;
            this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
            this.activatedAt = System.currentTimeMillis();
        }

        public String getExperimentId() { return experimentId; }
        public ScenarioType getScenario() { return scenario; }
        public String getTargetResource() { return targetResource; }
        public Map<String, String> getParameters() { return parameters; }
        public long getActivatedAt() { return activatedAt; }
    }

    private final Map<ScenarioType, ActiveFault> activeFaults = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeRedisExperimentId = new AtomicReference<>(null);
    private final AtomicBoolean redisFaultActive = new AtomicBoolean(false);
    private volatile AdmissionFailurePolicy scopedRedisPolicy = null;

    public void activateFault(ScenarioType scenario, String experimentId, String targetResource, Map<String, String> parameters) {
        ActiveFault fault = new ActiveFault(experimentId, scenario, targetResource, parameters);
        activeFaults.put(scenario, fault);
        if (scenario == ScenarioType.REDIS_INTERRUPTION) {
            activeRedisExperimentId.set(experimentId);
            redisFaultActive.set(true);
            String policy = parameters != null ? parameters.get("policy") : null;
            if ("FAIL_OPEN".equalsIgnoreCase(policy)) {
                scopedRedisPolicy = AdmissionFailurePolicy.FAIL_OPEN;
            } else {
                scopedRedisPolicy = AdmissionFailurePolicy.FAIL_CLOSED;
            }
        }
    }

    public void activateFault(ScenarioType scenario, String targetResource, Map<String, String> parameters) {
        activateFault(scenario, null, targetResource, parameters);
    }

    /**
     * Deactivates a fault only if the requesting experimentId matches the owner, or if experimentId is null.
     * Prevents a stale/abandoned experiment from deactivating a newer experiment's active fault.
     *
     * @param scenario the scenario to deactivate
     * @param experimentId the ID of the experiment requesting deactivation (or null for unconditional)
     * @return true if deactivated, false if not found or owned by a different experiment
     */
    public boolean deactivateFault(ScenarioType scenario, String experimentId) {
        ActiveFault current = activeFaults.get(scenario);
        if (current == null) {
            return false;
        }

        if (experimentId != null && current.getExperimentId() != null && !experimentId.equals(current.getExperimentId())) {
            return false;
        }

        activeFaults.remove(scenario);
        if (scenario == ScenarioType.REDIS_INTERRUPTION) {
            if (experimentId == null || experimentId.equals(activeRedisExperimentId.get())) {
                redisFaultActive.set(false);
                scopedRedisPolicy = null;
                activeRedisExperimentId.set(null);
            }
        }
        return true;
    }

    public void deactivateFault(ScenarioType scenario) {
        deactivateFault(scenario, null);
    }

    public String getFaultOwner(ScenarioType scenario) {
        ActiveFault fault = activeFaults.get(scenario);
        return fault != null ? fault.getExperimentId() : null;
    }

    public boolean isFaultActive(ScenarioType scenario) {
        return activeFaults.containsKey(scenario);
    }

    public boolean isFaultActiveFor(ScenarioType scenario, String experimentId) {
        ActiveFault fault = activeFaults.get(scenario);
        if (fault == null) {
            return false;
        }
        return experimentId == null || experimentId.equals(fault.getExperimentId());
    }

    public boolean isRedisFaultActive() {
        return redisFaultActive.get();
    }

    public String getActiveRedisExperimentId() {
        return activeRedisExperimentId.get();
    }

    public AdmissionFailurePolicy getScopedRedisPolicy(AdmissionFailurePolicy defaultPolicy) {
        AdmissionFailurePolicy current = scopedRedisPolicy;
        return current != null ? current : defaultPolicy;
    }

    public String getParameter(ScenarioType scenario, String key, String defaultValue) {
        ActiveFault fault = activeFaults.get(scenario);
        if (fault == null || fault.getParameters() == null) {
            return defaultValue;
        }
        return fault.getParameters().getOrDefault(key, defaultValue);
    }

    public void clearAll() {
        activeFaults.clear();
        redisFaultActive.set(false);
        scopedRedisPolicy = null;
        activeRedisExperimentId.set(null);
    }
}
