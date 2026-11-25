package com.orynx.orchestrator.resilience.adapter;

import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.resilience.model.ScenarioType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe registry managing active, scoped resilience faults with strict experiment ownership.
 * Uses a single immutable {@link ActiveFault} snapshot per scenario stored in a ConcurrentHashMap
 * and atomic bucket mutations ({@code compute}) to guarantee readers never observe torn state
 * and stale experiments can never deactivate newer experiments' registrations.
 */
@Component
public class ResilienceFaultRegistry {

    public static final class ActiveFault {
        private final String experimentId;
        private final ScenarioType scenario;
        private final String targetResource;
        private final Map<String, String> parameters;
        private final AdmissionFailurePolicy admissionFailurePolicy;
        private final long activatedAt;

        public ActiveFault(String experimentId, ScenarioType scenario, String targetResource, Map<String, String> parameters) {
            if (scenario == null) {
                throw new IllegalArgumentException("scenario must not be null");
            }
            if (experimentId == null || experimentId.isBlank()) {
                throw new IllegalArgumentException("experimentId must be non-null and non-blank");
            }
            this.experimentId = experimentId;
            this.scenario = scenario;
            this.targetResource = targetResource;
            this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
            if (scenario == ScenarioType.REDIS_INTERRUPTION) {
                String policy = this.parameters.get("policy");
                this.admissionFailurePolicy = "FAIL_OPEN".equalsIgnoreCase(policy)
                        ? AdmissionFailurePolicy.FAIL_OPEN
                        : AdmissionFailurePolicy.FAIL_CLOSED;
            } else {
                this.admissionFailurePolicy = null;
            }
            this.activatedAt = System.currentTimeMillis();
        }

        public String getExperimentId() { return experimentId; }
        public ScenarioType getScenario() { return scenario; }
        public String getTargetResource() { return targetResource; }
        public Map<String, String> getParameters() { return parameters; }
        public AdmissionFailurePolicy getAdmissionFailurePolicy() { return admissionFailurePolicy; }
        public long getActivatedAt() { return activatedAt; }
    }

    private final ConcurrentHashMap<ScenarioType, ActiveFault> activeFaults = new ConcurrentHashMap<>();

    /**
     * Atomically activates a fault owned by the specified non-blank experimentId.
     * Rejects conflicting activation if a different live experiment already owns an active fault
     * for the same scenario (Step 25.10 O-01). Re-activation by the same experimentId is permitted
     * and atomically updates the registration.
     *
     * @throws IllegalStateException if a different experiment already holds an active fault for this scenario
     */
    public void activateFault(ScenarioType scenario, String experimentId, String targetResource, Map<String, String> parameters) {
        ActiveFault fault = new ActiveFault(experimentId, scenario, targetResource, parameters);
        activeFaults.compute(scenario, (key, current) -> {
            if (current != null && !experimentId.equals(current.getExperimentId())) {
                throw new IllegalStateException("Scenario " + scenario
                        + " already has an active fault owned by experiment '" + current.getExperimentId()
                        + "'; cannot be overwritten by '" + experimentId + "'");
            }
            return fault;
        });
    }

    /**
     * Atomically deactivates a fault only if the requesting non-blank experimentId matches the current owner.
     * Prevents a stale/abandoned experiment from deactivating a newer experiment's active fault.
     *
     * @param scenario the scenario to deactivate (must not be null)
     * @param experimentId the non-null, non-blank ID of the experiment requesting deactivation
     * @return true if deactivated by the rightful owner, false if absent or owned by a different experiment
     */
    public boolean deactivateFault(ScenarioType scenario, String experimentId) {
        if (scenario == null) {
            throw new IllegalArgumentException("scenario must not be null");
        }
        if (experimentId == null || experimentId.isBlank()) {
            throw new IllegalArgumentException("experimentId must be non-null and non-blank");
        }

        AtomicBoolean removed = new AtomicBoolean(false);
        activeFaults.compute(scenario, (key, current) -> {
            if (current != null && experimentId.equals(current.getExperimentId())) {
                removed.set(true);
                return null;
            }
            return current;
        });
        return removed.get();
    }

    /**
     * Returns an atomic, immutable snapshot of the active fault for the given scenario, if any.
     */
    public Optional<ActiveFault> getActiveFault(ScenarioType scenario) {
        if (scenario == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeFaults.get(scenario));
    }

    /**
     * Returns an atomic, immutable snapshot of the active Redis interruption fault, if any.
     * Callers such as admission controllers must evaluate both presence and policy from this snapshot.
     */
    public Optional<ActiveFault> getActiveRedisFault() {
        return Optional.ofNullable(activeFaults.get(ScenarioType.REDIS_INTERRUPTION));
    }

    public String getFaultOwner(ScenarioType scenario) {
        if (scenario == null) {
            return null;
        }
        ActiveFault fault = activeFaults.get(scenario);
        return fault != null ? fault.getExperimentId() : null;
    }

    public boolean isFaultActive(ScenarioType scenario) {
        return scenario != null && activeFaults.containsKey(scenario);
    }

    public boolean isFaultActiveFor(ScenarioType scenario, String experimentId) {
        if (scenario == null || experimentId == null || experimentId.isBlank()) {
            return false;
        }
        ActiveFault fault = activeFaults.get(scenario);
        return fault != null && experimentId.equals(fault.getExperimentId());
    }

    public boolean isRedisFaultActive() {
        return activeFaults.containsKey(ScenarioType.REDIS_INTERRUPTION);
    }

    public String getActiveRedisExperimentId() {
        return getFaultOwner(ScenarioType.REDIS_INTERRUPTION);
    }

    public AdmissionFailurePolicy getScopedRedisPolicy(AdmissionFailurePolicy defaultPolicy) {
        ActiveFault fault = activeFaults.get(ScenarioType.REDIS_INTERRUPTION);
        return (fault != null && fault.getAdmissionFailurePolicy() != null)
                ? fault.getAdmissionFailurePolicy()
                : defaultPolicy;
    }

    public String getParameter(ScenarioType scenario, String key, String defaultValue) {
        if (scenario == null) {
            return defaultValue;
        }
        ActiveFault fault = activeFaults.get(scenario);
        if (fault == null || fault.getParameters() == null) {
            return defaultValue;
        }
        return fault.getParameters().getOrDefault(key, defaultValue);
    }

    public void clearAll() {
        activeFaults.clear();
    }
}
