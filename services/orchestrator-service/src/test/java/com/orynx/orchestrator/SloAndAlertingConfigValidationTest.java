package com.orynx.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.common.metrics.WorkflowMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloAndAlertingConfigValidationTest {

    private final Yaml yaml = new Yaml();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("docker-compose.yml"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate repository root containing docker-compose.yml");
        return current;
    }

    @Test
    @SuppressWarnings("unchecked")
    void prometheusConfigAndComposeRetentionSupport30DaySlos() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path promConfigPath = repoRoot.resolve("monitoring/prometheus/prometheus.yml");
        assertTrue(Files.exists(promConfigPath), "prometheus.yml must exist");

        Map<String, Object> config = yaml.load(Files.readString(promConfigPath));
        List<String> ruleFiles = (List<String>) config.get("rule_files");
        assertNotNull(ruleFiles);
        assertTrue(ruleFiles.contains("/etc/prometheus/rules/*.yml"));

        Map<String, Object> alerting = (Map<String, Object>) config.get("alerting");
        assertNotNull(alerting);
        assertTrue(alerting.toString().contains("alertmanager:9093"));

        List<Map<String, Object>> scrapeConfigs = (List<Map<String, Object>>) config.get("scrape_configs");
        assertTrue(scrapeConfigs.stream().anyMatch(job -> "alertmanager".equals(job.get("job_name"))));

        // M-04: Docker Compose Prometheus retention must support >= 30d rolling SLOs
        Map<String, Object> compose = yaml.load(Files.readString(repoRoot.resolve("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> prometheus = (Map<String, Object>) services.get("prometheus");
        List<String> cmd = (List<String>) prometheus.get("command");
        String cmdStr = String.join(" ", cmd);
        assertFalse(cmdStr.contains("--storage.tsdb.retention.time=7d"),
                "Prometheus retention must not be 7d when 30d SLO windows are used");
        assertTrue(cmdStr.contains("35d"), "Prometheus retention must default to >= 35d");
        assertTrue(cmdStr.contains("--storage.tsdb.retention.size"),
                "Prometheus should bound disk growth with retention.size");
    }

    @Test
    void workflowDurationHistogramSupportsLatencyAbove30sSloThreshold() {
        // M-01: Actual Micrometer scrape must have finite buckets > 30s so P95 > 30s can fire
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        WorkflowMetrics metrics = new WorkflowMetrics(registry);
        metrics.recordWorkflowCompleted("COMPLETED", 90_000L);
        String scrape = registry.scrape();
        assertTrue(scrape.contains("le=\"60.0\""), "Histogram must contain 60s bucket");
        assertTrue(scrape.contains("le=\"120.0\""), "Histogram must contain 120s bucket");
        assertTrue(scrape.contains("le=\"900.0\""), "Histogram must contain 15m ceiling bucket");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sloRecordingRulesAreMissingDataAwareLowCardinalityAndMatchDocs() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path rulesPath = repoRoot.resolve("monitoring/prometheus/rules/slo-recording-rules.yml");
        String rawContent = Files.readString(rulesPath);

        for (String forbidden : List.of("userId", "user_id", "workflowId", "workflow_id", "taskId", "task_id", "executionId", "requestId")) {
            assertFalse(rawContent.contains(forbidden),
                    "Recording rules must not reference high-cardinality label: " + forbidden);
        }

        // M-06: Must not use bare `or vector(0)` or `or vector(5)` that turn missing telemetry into 100% health
        assertFalse(rawContent.contains("or vector(0)"),
                "Recording rules must not use bare `or vector(0)` which hides missing telemetry");
        assertFalse(rawContent.contains("or vector(5)"),
                "Backend availability must not fabricate 5 services when up metrics are missing");

        Map<String, Object> root = yaml.load(rawContent);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertNotNull(groups);
        assertEquals(4, groups.size());

        Map<String, Integer> groupCounts = new HashMap<>();
        Set<String> recordedMetrics = new HashSet<>();
        int totalRules = 0;
        for (Map<String, Object> group : groups) {
            String name = (String) group.get("name");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
            assertNotNull(rules);
            groupCounts.put(name, rules.size());
            totalRules += rules.size();
            for (Map<String, Object> rule : rules) {
                String record = (String) rule.get("record");
                String expr = (String) rule.get("expr");
                assertNotNull(record);
                assertNotNull(expr);
                assertTrue(record.startsWith("orynx:"));
                recordedMetrics.add(record);
            }
        }

        // m-07: Verify docs/SLO.md rule counts match actual YAML counts
        String sloDoc = Files.readString(repoRoot.resolve("docs/SLO.md"));
        assertTrue(sloDoc.contains("**" + totalRules + " recording rules**"),
                "docs/SLO.md must document exact total recording rule count: " + totalRules);
        for (Map.Entry<String, Integer> entry : groupCounts.entrySet()) {
            assertTrue(sloDoc.contains("`" + entry.getKey() + "`** (" + entry.getValue() + " rules)"),
                    "docs/SLO.md must match actual rule count for " + entry.getKey() + ": " + entry.getValue());
        }

        for (String required : List.of(
                "orynx:slo:history_coverage_seconds",
                "orynx:slo:history_coverage_days",
                "orynx:api:error_budget_remaining:observed_30d",
                "orynx:api:error_budget_remaining:ratio_30d",
                "orynx:workflow:error_budget_remaining:observed_30d",
                "orynx:workflow:error_budget_remaining:ratio_30d",
                "orynx:task:error_budget_remaining:observed_30d",
                "orynx:task:error_budget_remaining:ratio_30d",
                "orynx:backend:availability_ratio"
        )) {
            assertTrue(recordedMetrics.contains(required), "Missing required recording rule: " + required);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void alertRulesHaveValidMetadataExistingRunbooksAndLowTrafficGuards() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path alertsPath = repoRoot.resolve("monitoring/prometheus/rules/alert-rules.yml");
        Map<String, Object> root = yaml.load(Files.readString(alertsPath));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertNotNull(groups);
        assertEquals(5, groups.size());

        Set<String> validSeverities = Set.of("critical", "warning", "info");
        Set<String> validCategories = Set.of("platform", "workflow", "task", "infrastructure", "slo");
        Map<String, Map<String, Object>> alertsByName = new HashMap<>();
        List<String> requiredSections = List.of(
                "## 1. What the Alert Means",
                "## 2. User / Business Impact",
                "## 3. Initial Investigation Steps",
                "## 4. Useful Commands",
                "## 5. Relevant Grafana Dashboards",
                "## 6. Relevant Prometheus Queries",
                "## 7. Likely Root Causes",
                "## 8. Safe Recovery Actions",
                "## 9. Escalation Criteria",
                "## 10. Verification After Recovery"
        );

        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
            for (Map<String, Object> rule : rules) {
                String alertName = (String) rule.get("alert");
                assertNotNull(alertName);
                alertsByName.put(alertName, rule);
                assertNotNull(rule.get("expr"));
                assertNotNull(rule.get("for"));

                Map<String, String> labels = (Map<String, String>) rule.get("labels");
                assertTrue(validSeverities.contains(labels.get("severity")));
                assertTrue(validCategories.contains(labels.get("category")));
                assertNotNull(labels.get("service"));

                Map<String, String> annotations = (Map<String, String>) rule.get("annotations");
                String runbookUrl = annotations.get("runbook_url");
                assertNotNull(runbookUrl);
                Path runbookPath = repoRoot.resolve(runbookUrl);
                assertTrue(Files.exists(runbookPath), "Missing runbook: " + runbookUrl);
                String runbookContent = Files.readString(runbookPath);
                for (String section : requiredSections) {
                    assertTrue(runbookContent.contains(section),
                            "Runbook " + runbookUrl + " missing section: " + section);
                }
            }
        }

        // m-01: BackendServiceDown must strip port from service label so Alertmanager service inhibition can match
        Map<String, String> backendDownLabels = (Map<String, String>) alertsByName.get("BackendServiceDown").get("labels");
        assertTrue(backendDownLabels.get("service").contains("reReplaceAll"),
                "BackendServiceDown service label must strip :port to match workflow/task service labels");

        // m-02: Critical ratio alerts must include minimum traffic guards
        assertTrue(alertsByName.get("ApiSloFastBurnCritical").get("expr").toString().contains("increase("),
                "ApiSloFastBurnCritical must include minimum request volume guard");
        assertTrue(alertsByName.get("WorkflowFailureRateHigh").get("expr").toString().contains("increase("),
                "WorkflowFailureRateHigh must include minimum workflow volume guard");
        assertTrue(alertsByName.get("TaskFailureRateHigh").get("expr").toString().contains("increase("),
                "TaskFailureRateHigh must include minimum task volume guard");

        // m-03: Core infrastructure exporter outages must be critical
        assertEquals("critical", ((Map<String, String>) alertsByName.get("PostgresExporterDown").get("labels")).get("severity"));
        assertEquals("critical", ((Map<String, String>) alertsByName.get("KafkaExporterDown").get("labels")).get("severity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void alertmanagerConfigIsHonestSafeAndScopedInhibition() throws Exception {
        Path repoRoot = resolveRepoRoot();
        String raw = Files.readString(repoRoot.resolve("monitoring/alertmanager/alertmanager.yml"));

        // M-02: Must never route webhooks to GET-only /-/healthy
        assertFalse(raw.contains("/-/healthy"),
                "Alertmanager config must not use GET-only /-/healthy as a webhook target");
        assertFalse(raw.contains("https://hooks.slack.com/services/T"));
        assertFalse(raw.contains("api_key:"));

        // M-03: Compose entrypoint must use render-config.sh (awk), not sed URL replacement
        String composeRaw = Files.readString(repoRoot.resolve("docker-compose.yml"));
        assertTrue(composeRaw.contains("render-config.sh"),
                "Alertmanager entrypoint must use safe render-config.sh script");
        assertFalse(composeRaw.contains("sed \"s|"),
                "Alertmanager entrypoint must not use unsafe sed replacement on webhook URL");

        Map<String, Object> config = yaml.load(raw);
        List<Map<String, Object>> inhibitRules = (List<Map<String, Object>>) config.get("inhibit_rules");
        assertNotNull(inhibitRules);
        assertTrue(inhibitRules.size() >= 4, "Expected 4 scoped inhibition rules");

        // M-05: No inhibition rule may suppress critical workflow/task/slo alerts via equal: []
        for (Map<String, Object> rule : inhibitRules) {
            List<String> targetMatchers = (List<String>) rule.get("target_matchers");
            String targetsJoined = String.join(",", targetMatchers);
            if (targetsJoined.contains("workflow") || targetsJoined.contains("task") || targetsJoined.contains("slo")) {
                assertTrue(targetsJoined.contains("severity = \"warning\""),
                        "Inhibition rules targeting workflow/task/slo must only suppress warnings, never critical alerts");
            }
            String sourcesJoined = String.join(",", (List<String>) rule.get("source_matchers"));
            assertFalse(sourcesJoined.contains("RedisUnavailable"),
                    "RedisUnavailable must not inhibit workflow/task/slo alerts when Redis is unused by services");
        }
    }

    @Test
    void grafanaSloDashboardHasNoDataBehaviorAndCorrectedPanels() throws Exception {
        Path repoRoot = resolveRepoRoot();
        JsonNode dashboard = objectMapper.readTree(
                Files.readString(repoRoot.resolve("monitoring/grafana/dashboards/orynx-slo-reliability.json"))
        );
        assertEquals("orynx-slo-reliability", dashboard.path("uid").asText());

        JsonNode panels = dashboard.path("panels");
        assertTrue(panels.isArray() && panels.size() >= 16);

        List<String> expressions = new ArrayList<>();
        for (JsonNode panel : panels) {
            assertEquals("prometheus", panel.path("datasource").path("uid").asText());
            String type = panel.path("type").asText();
            if ("stat".equals(type) || "gauge".equals(type)) {
                String noValue = panel.path("fieldConfig").path("defaults").path("noValue").asText();
                assertFalse(noValue.isBlank(),
                        "Stat/gauge panel '" + panel.path("title").asText() + "' must define explicit noValue text");
            }
            for (JsonNode target : panel.path("targets")) {
                expressions.add(target.path("expr").asText());
            }
        }
        String allExprs = String.join("\n", expressions);
        assertTrue(allExprs.contains("orynx:slo:history_coverage_days"));
        assertTrue(allExprs.contains("orynx:workflow:duration_p95_seconds:rate5m"));
    }
}
