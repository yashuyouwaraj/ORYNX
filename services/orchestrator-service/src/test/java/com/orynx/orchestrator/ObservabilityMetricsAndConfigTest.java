package com.orynx.orchestrator;

import com.orynx.orchestrator.common.config.PrometheusMetricsServer;
import com.orynx.orchestrator.common.metrics.WorkflowMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityMetricsAndConfigTest {

    @Test
    void workflowMetrics_recordsBoundedCountersAndDurationWithoutUnboundedTags() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        WorkflowMetrics metrics = new WorkflowMetrics(registry);

        metrics.recordWorkflowCreated();
        metrics.recordWorkflowStarted();
        metrics.recordWorkflowCompleted("COMPLETED", 1250L);
        metrics.recordWorkflowCompleted("FAILED", 500L);
        metrics.recordTaskExecuted("COMPLETED");
        metrics.recordTaskExecuted("FAILED");

        Counter created = registry.find("orynx.workflows.created").counter();
        Counter started = registry.find("orynx.workflows.started").counter();
        Counter completed = registry.find("orynx.workflows.completed").tag("status", "COMPLETED").counter();
        Counter failed = registry.find("orynx.workflows.completed").tag("status", "FAILED").counter();
        Timer completedTimer = registry.find("orynx.workflows.duration").tag("status", "COMPLETED").timer();
        Counter tasksCompleted = registry.find("orynx.tasks.executed").tag("status", "COMPLETED").counter();

        assertThat(created).isNotNull();
        assertThat(created.count()).isEqualTo(1.0);
        assertThat(started).isNotNull();
        assertThat(started.count()).isEqualTo(1.0);
        assertThat(completed).isNotNull();
        assertThat(completed.count()).isEqualTo(1.0);
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
        assertThat(completedTimer).isNotNull();
        assertThat(completedTimer.count()).isEqualTo(1L);
        assertThat(tasksCompleted).isNotNull();
        assertThat(tasksCompleted.count()).isEqualTo(1.0);

        String scraped = registry.scrape();
        assertThat(scraped).contains("orynx_workflows_total");
        assertThat(scraped).contains("orynx_workflows_started_total");
        assertThat(scraped).contains("orynx_workflows_completed_total");
        assertThat(scraped).contains("orynx_workflows_duration_seconds");
        assertThat(scraped).contains("orynx_tasks_executed_total");
        assertThat(scraped).doesNotContain("userId", "workflowId", "taskId", "requestId");
    }

    @Test
    void prometheusMetricsServer_exposesMetricsOnInternalPortAndRejectsPost() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        WorkflowMetrics metrics = new WorkflowMetrics(registry);
        metrics.recordWorkflowCreated();

        PrometheusMetricsServer server = new PrometheusMetricsServer(registry);
        ReflectionTestUtils.setField(server, "metricsPort", 0);
        server.start();
        try {
            Object httpServer = ReflectionTestUtils.getField(server, "server");
            int port = ((com.sun.net.httpserver.HttpServer) httpServer).getAddress().getPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> getResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(getResponse.statusCode()).isEqualTo(200);
            assertThat(getResponse.body()).contains("orynx_workflows_total 1.0");

            HttpResponse<String> postResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(postResponse.statusCode()).isEqualTo(405);
        } finally {
            server.stop();
        }
    }
}
