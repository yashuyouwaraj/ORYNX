package com.orynx.orchestrator.workflow;

import com.orynx.orchestrator.common.exception.UnauthorizedException;
import com.orynx.orchestrator.common.security.UserContext;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real PostgreSQL + MockMvc cross-user authorization tests verifying that
 * /api/v1/analytics and /api/v1/performance/** strictly isolate data by
 * authenticated userId and fail closed when identity is missing or spoofed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AnalyticsAndPerformanceAuthorizationPostgreSqlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowTaskRepository workflowTaskRepository;

    @Autowired
    private ExecutionEventRepository executionEventRepository;

    @Autowired
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Autowired
    private WorkflowAnalyticsService analyticsService;

    @Autowired
    private WorkflowPerformanceService performanceService;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private String generateToken(long userId) {
        return Jwts.builder()
                .setSubject("tenant-" + userId + "@orynx.test")
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000L))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    @DisplayName("Analytics and performance endpoints strictly isolate metrics per authenticated user")
    void analyticsAndPerformanceAreStrictlyUserScoped() throws Exception {
        executionEventRepository.deleteAll();
        workflowTaskRepository.deleteAll();
        workflowExecutionRepository.deleteAll();
        workflowRepository.deleteAll();

        long userA = 91001L;
        long userB = 91002L;
        long userEmpty = 91003L;

        // User A: 2 workflows (1 COMPLETED 100ms, 1 FAILED), 2 tasks (1 COMPLETED 50ms, 1 FAILED), 1 event
        Workflow wfA1 = workflowRepository.save(Workflow.builder()
                .name("UserA-Fast")
                .goal("gA1")
                .status(WorkflowStatus.COMPLETED)
                .startedAt(1000L)
                .completedAt(1100L)
                .userId(userA)
                .build());
        workflowRepository.save(Workflow.builder()
                .name("UserA-Failed")
                .goal("gA2")
                .status(WorkflowStatus.FAILED)
                .userId(userA)
                .build());
        workflowTaskRepository.save(WorkflowTask.builder()
                .name("TaskA1").executionOrder(1).status(TaskStatus.COMPLETED)
                .startedAt(1000L).completedAt(1050L).workflow(wfA1).build());
        workflowTaskRepository.save(WorkflowTask.builder()
                .name("TaskA2").executionOrder(2).status(TaskStatus.FAILED)
                .workflow(wfA1).build());
        executionEventRepository.save(ExecutionEvent.builder()
                .workflowId(wfA1.getId()).taskName("TaskA1").status("COMPLETED")
                .timestamp(1050L).generation(1L).eventId("ev-a-1").build());

        // User B: 1 COMPLETED workflow (5000ms), 1 COMPLETED task (4000ms), 2 events
        Workflow wfB1 = workflowRepository.save(Workflow.builder()
                .name("UserB-Secret-Slow")
                .goal("gB1")
                .status(WorkflowStatus.COMPLETED)
                .startedAt(1000L)
                .completedAt(6000L)
                .userId(userB)
                .build());
        workflowTaskRepository.save(WorkflowTask.builder()
                .name("TaskB1").executionOrder(1).status(TaskStatus.COMPLETED)
                .startedAt(1000L).completedAt(5000L).workflow(wfB1).build());
        executionEventRepository.save(ExecutionEvent.builder()
                .workflowId(wfB1.getId()).taskName("TaskB1").status("COMPLETED")
                .timestamp(5000L).generation(1L).eventId("ev-b-1").build());
        executionEventRepository.save(ExecutionEvent.builder()
                .workflowId(wfB1.getId()).taskName("TaskB1").status("STARTED")
                .timestamp(1000L).generation(1L).eventId("ev-b-2").build());

        String tokenA = generateToken(userA);
        String tokenB = generateToken(userB);
        String tokenEmpty = generateToken(userEmpty);

        // User A analytics: only User A's 2 workflows, 2 tasks, 1 event, 50% success/failure, 50ms avg
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(2))
                .andExpect(jsonPath("$.completedWorkflows").value(1))
                .andExpect(jsonPath("$.totalTasks").value(2))
                .andExpect(jsonPath("$.executedTaskCount").value(1))
                .andExpect(jsonPath("$.eventCount").value(1))
                .andExpect(jsonPath("$.averageExecutionTime").value(50.0))
                .andExpect(jsonPath("$.workflowSuccessRate").value(50.0));

        // User B analytics: only User B's 1 workflow, 1 task, 2 events, 100% success, 4000ms avg
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(1))
                .andExpect(jsonPath("$.completedWorkflows").value(1))
                .andExpect(jsonPath("$.totalTasks").value(1))
                .andExpect(jsonPath("$.executedTaskCount").value(1))
                .andExpect(jsonPath("$.eventCount").value(2))
                .andExpect(jsonPath("$.averageExecutionTime").value(4000.0))
                .andExpect(jsonPath("$.workflowSuccessRate").value(100.0));

        // Empty-dataset User C: all zeros, no cross-user leakage
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer " + tokenEmpty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(0))
                .andExpect(jsonPath("$.totalTasks").value(0))
                .andExpect(jsonPath("$.eventCount").value(0));

        // Performance endpoints for User A: only UserA-Fast (100ms), never UserB-Secret-Slow (5000ms)
        mockMvc.perform(get("/api/v1/performance").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].workflowName").value("UserA-Fast"))
                .andExpect(jsonPath("$[0].durationMs").value(100));

        mockMvc.perform(get("/api/v1/performance/summary").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fastestWorkflow").value("UserA-Fast"))
                .andExpect(jsonPath("$.slowestWorkflow").value("UserA-Fast"))
                .andExpect(jsonPath("$.completedWorkflowCount").value(1))
                .andExpect(jsonPath("$.averageDuration").value(100.0));

        mockMvc.perform(get("/api/v1/performance/health").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].workflowName").value("UserA-Fast"))
                .andExpect(jsonPath("$[0].health").value("HEALTHY"));

        // Empty-dataset User C performance endpoints
        mockMvc.perform(get("/api/v1/performance").header("Authorization", "Bearer " + tokenEmpty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/v1/performance/summary").header("Authorization", "Bearer " + tokenEmpty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedWorkflowCount").doesNotExist());
        mockMvc.perform(get("/api/v1/performance/health").header("Authorization", "Bearer " + tokenEmpty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Missing, invalid, or spoofed identities fail closed on analytics and performance endpoints")
    void missingAndSpoofedIdentitiesFailClosed() throws Exception {
        for (String path : new String[]{
                "/api/v1/analytics",
                "/api/v1/performance",
                "/api/v1/performance/summary",
                "/api/v1/performance/health"
        }) {
            // Unauthenticated
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized());

            // Spoofed X-User-Id mismatching JWT
            mockMvc.perform(get(path)
                            .header("Authorization", "Bearer " + generateToken(91001L))
                            .header("X-User-Id", "91002"))
                    .andExpect(status().isUnauthorized());
        }

        // Service-layer fail-closed check when UserContext is absent
        UserContext.clear();
        assertThatThrownBy(() -> analyticsService.getAnalytics()).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> performanceService.getWorkflowPerformance()).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> performanceService.getPerformanceSummary()).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> performanceService.getWorkflowHealth()).isInstanceOf(UnauthorizedException.class);
    }
}
