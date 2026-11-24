package com.orynx.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    void prometheusConfigReferencesRulesAndAlertmanager() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path promConfigPath = repoRoot.resolve("monitoring/prometheus/prometheus.yml");
        assertTrue(Files.exists(promConfigPath), "prometheus.yml must exist");

        Map<String, Object> config = yaml.load(Files.readString(promConfigPath));
        List<String> ruleFiles = (List<String>) config.get("rule_files");
        assertNotNull(ruleFiles, "prometheus.yml must define rule_files");
        assertTrue(ruleFiles.contains("/etc/prometheus/rules/*.yml"),
                "prometheus.yml must load /etc/prometheus/rules/*.yml");

        Map<String, Object> alerting = (Map<String, Object>) config.get("alerting");
        assertNotNull(alerting, "prometheus.yml must configure alerting section");
        String alertingRaw = alerting.toString();
        assertTrue(alertingRaw.contains("alertmanager:9093"),
                "prometheus.yml must target alertmanager:9093");

        List<Map<String, Object>> scrapeConfigs = (List<Map<String, Object>>) config.get("scrape_configs");
        boolean scrapesAlertmanager = scrapeConfigs.stream()
                .anyMatch(job -> "alertmanager".equals(job.get("job_name")));
        assertTrue(scrapesAlertmanager, "prometheus.yml must scrape alertmanager job");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sloRecordingRulesAreValidZeroSafeAndLowCardinality() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path rulesPath = repoRoot.resolve("monitoring/prometheus/rules/slo-recording-rules.yml");
        assertTrue(Files.exists(rulesPath), "slo-recording-rules.yml must exist");

        String rawContent = Files.readString(rulesPath);
        // High-cardinality labels must never appear in recording rules
        for (String forbidden : List.of("userId", "user_id", "workflowId", "workflow_id", "taskId", "task_id", "executionId", "requestId")) {
            assertFalse(rawContent.contains(forbidden),
                    "Recording rules must not group by or reference high-cardinality label: " + forbidden);
        }

        Map<String, Object> root = yaml.load(rawContent);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertNotNull(groups);
        assertEquals(4, groups.size(), "Expected 4 SLO recording rule groups (API, Workflow, Task, Infrastructure)");

        Set<String> recordedMetrics = new HashSet<>();
        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
            assertNotNull(rules);
            for (Map<String, Object> rule : rules) {
                String record = (String) rule.get("record");
                String expr = (String) rule.get("expr");
                assertNotNull(record, "Every rule in slo-recording-rules.yml must have a 'record' name");
                assertNotNull(expr, "Recording rule " + record + " must have a non-null 'expr'");
                assertTrue(record.startsWith("orynx:"), "Recording rule must follow 'orynx:...' naming convention: " + record);
                recordedMetrics.add(record);
            }
        }

        // Verify mandatory SLIs, SLO targets, error budgets, and burn rates exist
        List<String> requiredRecords = List.of(
                "orynx:api:slo_target_availability",
                "orynx:api:slo_target_p95_seconds",
                "orynx:api:availability:ratio_rate5m",
                "orynx:api:availability:ratio_rate1h",
                "orynx:api:availability:ratio_rate6h",
                "orynx:api:availability:ratio_rate24h",
                "orynx:api:availability:ratio_rate7d",
                "orynx:api:availability:ratio_rate30d",
                "orynx:api:latency_p95:rate5m",
                "orynx:api:latency_p99:rate5m",
                "orynx:api:error_budget_remaining:ratio_30d",
                "orynx:api:burn_rate:5m",
                "orynx:api:burn_rate:30m",
                "orynx:api:burn_rate:1h",
                "orynx:api:burn_rate:6h",
                "orynx:workflow:slo_target_success_ratio",
                "orynx:workflow:slo_target_p95_seconds",
                "orynx:workflow:success_ratio:rate5m",
                "orynx:workflow:success_ratio:rate1h",
                "orynx:workflow:success_ratio:rate6h",
                "orynx:workflow:success_ratio:rate24h",
                "orynx:workflow:success_ratio:rate7d",
                "orynx:workflow:success_ratio:rate30d",
                "orynx:workflow:duration_avg_seconds:rate5m",
                "orynx:workflow:duration_p95_seconds:rate5m",
                "orynx:workflow:error_budget_remaining:ratio_30d",
                "orynx:workflow:burn_rate:5m",
                "orynx:workflow:burn_rate:1h",
                "orynx:workflow:burn_rate:6h",
                "orynx:task:slo_target_success_ratio",
                "orynx:task:success_ratio:rate5m",
                "orynx:task:success_ratio:rate30d",
                "orynx:task:error_budget_remaining:ratio_30d",
                "orynx:task:burn_rate:5m",
                "orynx:task:burn_rate:1h",
                "orynx:backend:availability_ratio",
                "orynx:postgres:connection_utilization_ratio",
                "orynx:kafka:consumer_lag:by_group_topic",
                "orynx:otel:export_success_ratio:rate5m"
        );
        for (String required : requiredRecords) {
            assertTrue(recordedMetrics.contains(required), "Missing required SLO recording rule: " + required);
        }

        // Verify denominator protection against division by zero
        assertTrue(rawContent.contains("clamp_min("),
                "SLO ratio recording rules must use clamp_min to prevent division-by-zero NaN during idle periods");
    }

    @Test
    @SuppressWarnings("unchecked")
    void alertRulesHaveCompleteAnnotationsMetadataAndExistingRunbooks() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path alertsPath = repoRoot.resolve("monitoring/prometheus/rules/alert-rules.yml");
        assertTrue(Files.exists(alertsPath), "alert-rules.yml must exist");

        Map<String, Object> root = yaml.load(Files.readString(alertsPath));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertNotNull(groups);
        assertEquals(5, groups.size(), "Expected 5 alert rule groups");

        Set<String> validSeverities = Set.of("critical", "warning", "info");
        Set<String> validCategories = Set.of("platform", "workflow", "task", "infrastructure", "slo");
        Set<String> alertNames = new HashSet<>();
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
            assertNotNull(rules);
            for (Map<String, Object> rule : rules) {
                String alertName = (String) rule.get("alert");
                assertNotNull(alertName, "Every alert rule must define 'alert'");
                alertNames.add(alertName);

                assertNotNull(rule.get("expr"), alertName + " must define 'expr'");
                assertNotNull(rule.get("for"), alertName + " must define a 'for' duration to prevent flapping");

                Map<String, String> labels = (Map<String, String>) rule.get("labels");
                assertNotNull(labels, alertName + " must define labels");
                assertTrue(validSeverities.contains(labels.get("severity")),
                        alertName + " must have valid severity (critical, warning, info)");
                assertTrue(validCategories.contains(labels.get("category")),
                        alertName + " must have valid category");
                assertNotNull(labels.get("service"), alertName + " must define service label");

                Map<String, String> annotations = (Map<String, String>) rule.get("annotations");
                assertNotNull(annotations, alertName + " must define annotations");
                assertNotNull(annotations.get("summary"), alertName + " must define summary annotation");
                assertNotNull(annotations.get("description"), alertName + " must define description annotation");
                assertNotNull(annotations.get("impact"), alertName + " must define impact annotation");

                String runbookUrl = annotations.get("runbook_url");
                assertNotNull(runbookUrl, alertName + " must define runbook_url annotation");
                Path runbookPath = repoRoot.resolve(runbookUrl);
                assertTrue(Files.exists(runbookPath),
                        alertName + " references non-existent runbook: " + runbookUrl);

                String runbookContent = Files.readString(runbookPath);
                for (String section : requiredSections) {
                    assertTrue(runbookContent.contains(section),
                            "Runbook " + runbookUrl + " is missing required section: " + section);
                }
            }
        }

        List<String> expectedAlerts = List.of(
                "BackendServiceDown", "PrometheusTargetDown", "OtelCollectorDown",
                "PostgresExporterDown", "RedisExporterDown", "KafkaExporterDown", "AlertmanagerDown",
                "WorkflowFailureRateHigh", "WorkflowExecutionLatencyHigh", "WorkflowCompletionDegraded", "WorkflowRetryRateElevated",
                "TaskFailureRateHigh", "TaskExecutionStalled",
                "PostgreSQLUnavailable", "PostgreSQLConnectionsHigh", "RedisUnavailable",
                "KafkaUnavailable", "KafkaConsumerLagHigh", "OtelCollectorSpanDrops",
                "ApiSloFastBurnCritical", "ApiSloSlowBurnWarning",
                "WorkflowSloFastBurnCritical", "WorkflowSloSlowBurnWarning",
                "TaskSloFastBurnCritical", "ErrorBudgetExhausted"
        );
        for (String expected : expectedAlerts) {
            assertTrue(alertNames.contains(expected), "Missing required alert rule: " + expected);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void alertmanagerConfigHasRoutingGroupingInhibitionAndNoSecrets() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path amPath = repoRoot.resolve("monitoring/alertmanager/alertmanager.yml");
        assertTrue(Files.exists(amPath), "alertmanager.yml must exist");

        String raw = Files.readString(amPath);
        assertFalse(raw.contains("https://hooks.slack.com/services/T"), "alertmanager.yml must not contain hardcoded Slack secrets");
        assertFalse(raw.contains("api_key:"), "alertmanager.yml must not contain hardcoded API keys");

        Map<String, Object> config = yaml.load(raw);
        Map<String, Object> route = (Map<String, Object>) config.get("route");
        assertNotNull(route, "alertmanager.yml must define root route");
        List<String> groupBy = (List<String>) route.get("group_by");
        assertEquals(List.of("alertname", "service", "category"), groupBy,
                "Root route must group by alertname, service, and category");

        List<Map<String, Object>> childRoutes = (List<Map<String, Object>>) route.get("routes");
        assertNotNull(childRoutes);
        assertTrue(childRoutes.size() >= 5, "Expected at least 5 severity/category child routes");

        List<Map<String, Object>> inhibitRules = (List<Map<String, Object>>) config.get("inhibit_rules");
        assertNotNull(inhibitRules);
        assertTrue(inhibitRules.size() >= 3, "Expected at least 3 alert inhibition rules");

        List<Map<String, Object>> receivers = (List<Map<String, Object>>) config.get("receivers");
        assertNotNull(receivers);
        assertEquals(6, receivers.size(), "Expected 6 configured webhook receivers");
    }

    @Test
    void grafanaSloDashboardIsValidAndReferencesRecordedSliMetrics() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path dashboardPath = repoRoot.resolve("monitoring/grafana/dashboards/orynx-slo-reliability.json");
        assertTrue(Files.exists(dashboardPath), "orynx-slo-reliability.json must exist");

        JsonNode dashboard = objectMapper.readTree(Files.readString(dashboardPath));
        assertEquals("orynx-slo-reliability", dashboard.path("uid").asText());
        assertEquals("OrynX SLO & Operational Reliability", dashboard.path("title").asText());

        JsonNode panels = dashboard.path("panels");
        assertTrue(panels.isArray() && panels.size() >= 14,
                "SLO dashboard must contain at least 14 panels covering SLIs, error budgets, burn rates, alerts, and infra");

        List<String> expressions = new ArrayList<>();
        for (JsonNode panel : panels) {
            assertEquals("prometheus", panel.path("datasource").path("uid").asText(),
                    "All panels must use provisioned prometheus datasource uid");
            for (JsonNode target : panel.path("targets")) {
                expressions.add(target.path("expr").asText());
            }
        }
        String allExprs = String.join("\n", expressions);
        for (String expectedMetric : List.of(
                "orynx:api:availability:ratio_rate5m",
                "orynx:workflow:success_ratio:rate5m",
                "orynx:task:success_ratio:rate5m",
                "orynx:api:error_budget_remaining:ratio_30d",
                "orynx:workflow:error_budget_remaining:ratio_30d",
                "orynx:task:error_budget_remaining:ratio_30d",
                "orynx:api:burn_rate:5m",
                "orynx:workflow:burn_rate:5m",
                "orynx:api:latency_p95:rate5m",
                "orynx:workflow:duration_p95_seconds:rate5m",
                "ALERTS{alertstate=\"firing\"}",
                "orynx:backend:availability_ratio"
        )) {
            assertTrue(allExprs.contains(expectedMetric),
                    "Grafana SLO dashboard must query metric: " + expectedMetric);
        }
    }
}
