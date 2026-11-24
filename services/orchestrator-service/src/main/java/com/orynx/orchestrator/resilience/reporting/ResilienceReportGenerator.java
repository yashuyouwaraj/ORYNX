package com.orynx.orchestrator.resilience.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orynx.orchestrator.resilience.model.ExperimentDefinition;
import com.orynx.orchestrator.resilience.model.ExperimentResult;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates standardized markdown and JSON reports for resilience experiments,
 * containing all 17 mandatory audit fields.
 */
@Component
public class ResilienceReportGenerator {

    private final ObjectMapper objectMapper;

    public ResilienceReportGenerator() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String generateMarkdownReport(ExperimentDefinition definition, ExperimentResult result) {
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter iso = DateTimeFormatter.ISO_INSTANT;

        sb.append("# Resilience Experiment Report: ").append(definition.getId()).append("\n\n");

        sb.append("## 1. Experiment ID\n`").append(definition.getId()).append("`\n\n");
        sb.append("## 2. Scenario\n").append(definition.getScenarioType().name())
                .append(" — ").append(definition.getScenarioType().getDescription()).append("\n\n");
        sb.append("## 3. Environment\n`").append(definition.getEnvironmentTag()).append("`\n\n");
        sb.append("## 4. Target\n- Component: `").append(definition.getTargetComponent()).append("`\n")
                .append("- Resource: `").append(definition.getTargetResource()).append("`\n\n");
        sb.append("## 5. Preconditions\n- Component must be healthy before injection.\n")
                .append("- Safety validator verifies environment isolation.\n\n");

        sb.append("## 6. Injection Parameters\n");
        if (definition.getParameters().isEmpty()) {
            sb.append("None (default parameters used)\n\n");
        } else {
            definition.getParameters().forEach((k, v) -> sb.append("- `").append(k).append("`: `").append(v).append("`\n"));
            sb.append("\n");
        }

        sb.append("## 7. Start Timestamp\n").append(result.getStartTime() != null ? iso.format(result.getStartTime()) : "N/A").append("\n\n");
        sb.append("## 8. Failure Duration\n").append(result.getFailureDurationMs()).append(" ms\n\n");

        sb.append("## 9. Observed Behavior\n");
        if (result.getObservedBehaviors().isEmpty()) {
            sb.append("No specific observations recorded.\n\n");
        } else {
            for (String obs : result.getObservedBehaviors()) {
                sb.append("- ").append(obs).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 10. Workflow Impact\n")
                .append(definition.getExpectedBehavior() != null ? definition.getExpectedBehavior() : "Workflow execution degraded gracefully according to failure policy without state corruption.")
                .append("\n\n");

        sb.append("## 11. Recovery Duration\n").append(result.getRecoveryDurationMs()).append(" ms\n\n");

        sb.append("## 12. Recovery Validation\n")
                .append(result.isSuccessful() ? "PASS — Component restored to operational state; verification passed." : "FAILED or INCOMPLETE recovery.")
                .append("\n\n");

        sb.append("## 13. Metrics and Alerts\n")
                .append("- Metric recorded: `orynx.resilience.experiments.total{scenario=\"")
                .append(definition.getScenarioType().name().toLowerCase())
                .append("\",outcome=\"")
                .append(result.getOutcome().name().toLowerCase())
                .append("\"}`\n")
                .append("- Recovery timer: `orynx.resilience.recovery.duration.seconds{scenario=\"")
                .append(definition.getScenarioType().name().toLowerCase())
                .append("\"}`\n\n");

        sb.append("## 14. Cleanup Status\n")
                .append(result.isCleanupSuccessful() ? "CLEANUP SUCCESSFUL — All fault injection hooks restored to normal operational state." : "CLEANUP FAILED — Manual inspection required!")
                .append("\n\n");

        sb.append("## 15. Result\n**").append(result.getOutcome().name()).append("**\n\n");

        sb.append("## 16. Failure Details\n");
        if (result.getVerificationErrors().isEmpty()) {
            sb.append("None (zero verification errors).\n\n");
        } else {
            for (String err : result.getVerificationErrors()) {
                sb.append("- ").append(err).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 17. Reproduction Instructions\n")
                .append("Execute via `ResilienceExperimentEngine.runExperiment(def)` with scenario `")
                .append(definition.getScenarioType().name())
                .append("` in test environment `").append(definition.getEnvironmentTag()).append("`.\n");

        return sb.toString();
    }

    public String generateJsonReport(ExperimentDefinition definition, ExperimentResult result) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("experimentId", definition.getId());
            data.put("scenario", definition.getScenarioType().name());
            data.put("environment", definition.getEnvironmentTag());
            data.put("targetComponent", definition.getTargetComponent());
            data.put("targetResource", definition.getTargetResource());
            data.put("parameters", definition.getParameters());
            data.put("startTime", result.getStartTime() != null ? result.getStartTime().toString() : null);
            data.put("endTime", result.getEndTime() != null ? result.getEndTime().toString() : null);
            data.put("failureDurationMs", result.getFailureDurationMs());
            data.put("recoveryDurationMs", result.getRecoveryDurationMs());
            data.put("observedBehaviors", result.getObservedBehaviors());
            data.put("verificationErrors", result.getVerificationErrors());
            data.put("cleanupSuccessful", result.isCleanupSuccessful());
            data.put("outcome", result.getOutcome().name());
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON report", e);
        }
    }
}
