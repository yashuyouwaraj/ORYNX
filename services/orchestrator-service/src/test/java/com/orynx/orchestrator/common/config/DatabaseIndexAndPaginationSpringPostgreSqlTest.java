package com.orynx.orchestrator.common.config;

import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.workflow.*;
import com.orynx.orchestrator.workflow.dto.WorkflowHealthResponse;
import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.event.ExecutionTimelineService;
import com.orynx.orchestrator.workflow.event.dto.ExecutionTimelineResponse;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end PostgreSQL Spring Data JPA + MockMvc verification of:
 * - Workflow sorting (default, name ASC, name DESC, id tie-breaking)
 * - Sort-field allowlisting (400 Bad Request on unknown/relation paths)
 * - @EntityGraph(attributePaths = "dependsOnWorkflow") pagination integrity
 * - Ownership isolation across page boundaries
 * - Duplicate-timestamp timeline pagination tie-breaking
 * - Global aggregate baseline in WorkflowPerformanceService.getWorkflowHealth() (> 100 workflows)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DatabaseIndexAndPaginationSpringPostgreSqlTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ExecutionEventRepository executionEventRepository;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private ExecutionTimelineService executionTimelineService;

    @Autowired
    private WorkflowPerformanceService workflowPerformanceService;

    @Autowired
    private WorkflowTaskRepository workflowTaskRepository;

    @Autowired
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Autowired
    private MockMvc mockMvc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String generateToken(long userId) {
        return Jwts.builder()
                .setSubject("audit-user-" + userId + "@orynx.test")
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000L))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    @DisplayName("PostgreSQL JPA pagination honors custom sorting, tie-breakers, EntityGraph, and ownership boundaries")
    void workflowPaginationSortingEntityGraphAndOwnershipWorkOnPostgreSql() {
        long userA = 70001L;
        long userB = 70002L;

        Workflow dep = workflowRepository.save(Workflow.builder()
                .name("Alpha-Dep")
                .goal("dep")
                .status(WorkflowStatus.COMPLETED)
                .userId(userA)
                .build());

        Workflow w1 = workflowRepository.save(Workflow.builder()
                .name("Beta")
                .goal("g1")
                .status(WorkflowStatus.RUNNING)
                .dependsOnWorkflow(dep)
                .userId(userA)
                .build());

        Workflow w2 = workflowRepository.save(Workflow.builder()
                .name("Beta")
                .goal("g2")
                .status(WorkflowStatus.RUNNING)
                .userId(userA)
                .build());

        Workflow otherUserWf = workflowRepository.save(Workflow.builder()
                .name("Aaa-Other-User")
                .goal("other")
                .status(WorkflowStatus.RUNNING)
                .userId(userB)
                .build());

        // Default sort: id DESC, isolated to userA
        Page<Workflow> defaultPage = workflowService.getAllWorkflows(PageRequest.of(0, 2), userA);
        assertThat(defaultPage.getTotalElements()).isEqualTo(3L);
        assertThat(defaultPage.getContent()).extracting(Workflow::getId)
                .containsExactly(w2.getId(), w1.getId())
                .doesNotContain(otherUserWf.getId());

        // Custom sort: name ASC with deterministic id DESC tie-breaker for duplicate "Beta"
        Page<Workflow> nameAsc = workflowService.getAllWorkflows(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "name")),
                userA
        );
        assertThat(nameAsc.getContent()).extracting(Workflow::getId)
                .containsExactly(dep.getId(), w2.getId(), w1.getId());
        assertThat(nameAsc.getContent().get(2).getDependsOnWorkflow()).isNotNull();
        assertThat(nameAsc.getContent().get(2).getDependsOnWorkflow().getName()).isEqualTo("Alpha-Dep");

        // Custom sort: name DESC with deterministic id DESC tie-breaker
        Page<Workflow> nameDesc = workflowService.getAllWorkflows(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "name")),
                userA
        );
        assertThat(nameDesc.getContent()).extracting(Workflow::getId)
                .containsExactly(w2.getId(), w1.getId(), dep.getId());
    }

    @Test
    @DisplayName("Invalid and relation-path sort fields are rejected with HTTP 400 Bad Request")
    void invalidSortPropertiesReturnBadRequest() throws Exception {
        String token = generateToken(70001L);

        mockMvc.perform(get("/api/v1/workflows")
                        .header("Authorization", "Bearer " + token)
                        .param("sort", "nonExistentField,asc"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/workflows")
                        .header("Authorization", "Bearer " + token)
                        .param("sort", "dependsOnWorkflow.name,asc"))
                .andExpect(status().isBadRequest());

        assertThatThrownBy(() -> workflowService.getAllWorkflows(
                PageRequest.of(0, 10, Sort.by("dependsOnWorkflow.name")),
                70001L
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Timeline pagination breaks duplicate timestamps deterministically by id DESC across pages")
    void timelinePaginationBreaksDuplicateTimestampsDeterministically() {
        long userId = 70003L;
        Workflow wf = workflowRepository.save(Workflow.builder()
                .name("Timeline-WF")
                .goal("timeline")
                .status(WorkflowStatus.RUNNING)
                .userId(userId)
                .build());

        long sharedTs = 1700000000000L;
        for (int i = 1; i <= 4; i++) {
            executionEventRepository.save(ExecutionEvent.builder()
                    .workflowId(wf.getId())
                    .taskName("Task-" + i)
                    .status("COMPLETED")
                    .timestamp(sharedTs)
                    .generation(1L)
                    .eventId("dup-ts-" + wf.getId() + "-" + i)
                    .build());
        }

        Page<ExecutionTimelineResponse> page0 = executionTimelineService.getWorkflowTimeline(
                wf.getId(), PageRequest.of(0, 2));
        Page<ExecutionTimelineResponse> page1 = executionTimelineService.getWorkflowTimeline(
                wf.getId(), PageRequest.of(1, 2));

        assertThat(page0.getTotalElements()).isEqualTo(4L);
        assertThat(page0.getContent()).extracting(ExecutionTimelineResponse::getTaskName)
                .containsExactly("Task-4", "Task-3");
        assertThat(page1.getContent()).extracting(ExecutionTimelineResponse::getTaskName)
                .containsExactly("Task-2", "Task-1");
    }

    @Test
    @DisplayName("WorkflowPerformanceService.getWorkflowHealth uses global aggregate average when > 100 completed workflows exist")
    void workflowHealthUsesGlobalAverageAbove100Workflows() {
        workflowTaskRepository.deleteAll();
        workflowExecutionRepository.deleteAll();
        workflowRepository.deleteAll();

        long userId = 70004L;
        for (int i = 0; i < 99; i++) {
            workflowRepository.save(Workflow.builder()
                    .name("Fast-" + i)
                    .goal("perf")
                    .status(WorkflowStatus.COMPLETED)
                    .startedAt(1000L)
                    .completedAt(1100L) // 100ms
                    .userId(userId)
                    .build());
        }
        // Target-120ms is in the fastest-100 slice (local avg 100.2ms -> would be WARNING if skewed),
        // but well below the global average (~1000ms) once the 10 slow workflows are included.
        workflowRepository.save(Workflow.builder()
                .name("Target-120ms")
                .goal("perf")
                .status(WorkflowStatus.COMPLETED)
                .startedAt(1000L)
                .completedAt(1120L)
                .userId(userId)
                .build());
        for (int i = 0; i < 10; i++) {
            workflowRepository.save(Workflow.builder()
                    .name("Slow-" + i)
                    .goal("perf")
                    .status(WorkflowStatus.COMPLETED)
                    .startedAt(1000L)
                    .completedAt(11000L) // 10000ms
                    .userId(userId)
                    .build());
        }

        List<WorkflowHealthResponse> health = workflowPerformanceService.getWorkflowHealth(userId);
        assertThat(health).hasSize(100);
        WorkflowHealthResponse target120 = health.stream()
                .filter(h -> "Target-120ms".equals(h.getWorkflowName()))
                .findFirst()
                .orElseThrow();
        assertThat(target120.getHealth()).isEqualTo("HEALTHY");
    }
}
