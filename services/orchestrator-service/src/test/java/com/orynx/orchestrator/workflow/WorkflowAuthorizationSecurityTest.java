package com.orynx.orchestrator.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orynx.orchestrator.common.exception.GlobalExceptionHandler;
import com.orynx.orchestrator.common.security.OrchestratorJwtService;
import com.orynx.orchestrator.common.security.UserContext;
import com.orynx.orchestrator.common.security.UserContextFilter;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.kafka.*;
import com.orynx.orchestrator.workflow.dto.CreateWorkflowRequest;
import com.orynx.orchestrator.workflow.task.TaskStatus;
import com.orynx.orchestrator.workflow.task.WorkflowExecutionEngine;
import com.orynx.orchestrator.workflow.task.WorkflowTask;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class WorkflowAuthorizationSecurityTest {

    private static final String TEST_SECRET = "testSecretKeyForJwtSigning1234567890!@#$";
    private static final String ATTACKER_SECRET = "attackerSecretKeyForJwtSigning1234567890!@#";

    private static final Long USER_A_ID = 101L;
    private static final String USER_A_EMAIL = "userA@orynx.io";

    private static final Long USER_B_ID = 202L;
    private static final String USER_B_EMAIL = "userB@orynx.io";

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private KafkaProducer kafkaProducer;
    @Mock
    private WorkflowEventPublisher workflowEventPublisher;
    @Mock
    private WorkflowTaskRepository workflowTaskRepository;
    @Mock
    private WorkflowExecutionEngine workflowExecutionEngine;
    @Mock
    private ExecutionRequestProducer executionRequestProducer;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private WorkflowCancellationProducer workflowCancellationProducer;
    @Mock
    private WorkflowPauseProducer workflowPauseProducer;
    @Mock
    private WorkflowResumeProducer workflowResumeProducer;

    private WorkflowService workflowService;
    private OrchestratorJwtService jwtService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jwtService = new OrchestratorJwtService(TEST_SECRET);
        workflowService = new WorkflowService(
                workflowRepository,
                kafkaProducer,
                workflowEventPublisher,
                workflowTaskRepository,
                workflowExecutionEngine,
                executionRequestProducer,
                workflowExecutionRepository,
                workflowCancellationProducer,
                workflowPauseProducer,
                workflowResumeProducer
        );

        WorkflowController controller = new WorkflowController(workflowService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .addFilters(new UserContextFilter(jwtService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private String generateToken(String email, Long userId, long durationMs, String secret) {
        Key key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + durationMs));

        if (userId != null) {
            builder.claim("userId", userId);
        }

        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    // ==========================================
    // Phase 12 - Authentication Tests
    // ==========================================

    @Test
    @DisplayName("1. Anonymous request without token must be rejected with 401")
    void shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication required: missing Bearer token"));
    }

    @Test
    @DisplayName("2. Valid JWT must be accepted")
    void shouldAcceptValidToken() throws Exception {
        String token = generateToken(USER_A_EMAIL, USER_A_ID, 3600000L, TEST_SECRET);

        Workflow workflowA = Workflow.builder()
                .id(1L)
                .name("Workflow A")
                .goal("Goal A")
                .status(WorkflowStatus.CREATED)
                .userId(USER_A_ID)
                .build();

        when(workflowRepository.findByIdAndUserId(1L, USER_A_ID)).thenReturn(Optional.of(workflowA));

        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Workflow A"));
    }

    @Test
    @DisplayName("3. Invalid JWT signature must be rejected with 401")
    void shouldRejectInvalidTokenSignature() throws Exception {
        String token = generateToken(USER_A_EMAIL, USER_A_ID, 3600000L, ATTACKER_SECRET);

        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid JWT token signature or format"));
    }

    @Test
    @DisplayName("4. Expired JWT must be rejected with 401")
    void shouldRejectExpiredToken() throws Exception {
        String token = generateToken(USER_A_EMAIL, USER_A_ID, -1000L, TEST_SECRET);

        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("JWT token has expired"));
    }

    @Test
    @DisplayName("5. JWT missing userId claim must be rejected with 401")
    void shouldRejectTokenMissingUserIdClaim() throws Exception {
        String token = generateToken(USER_A_EMAIL, null, 3600000L, TEST_SECRET);

        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Token missing required userId claim"));
    }

    @Test
    @DisplayName("6. Malformed Bearer token string must be rejected with 401")
    void shouldRejectMalformedToken() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("Authorization", "Bearer not.a.valid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid JWT token signature or format"));
    }

    // ==========================================
    // Phase 12 - Header Spoofing Tests
    // ==========================================

    @Test
    @DisplayName("7. Direct client submitting fake X-User-Id without valid token must be rejected with 401")
    void shouldRejectDirectFakeUserIdHeader() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("X-User-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required: direct X-User-Id spoofing prohibited"));
    }

    @Test
    @DisplayName("8. Caller submitting X-User-Id mismatched with JWT claim must be rejected with 401")
    void shouldRejectMismatchedUserIdHeader() throws Exception {
        String token = generateToken(USER_A_EMAIL, USER_A_ID, 3600000L, TEST_SECRET);

        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "999")) // Mismatched ID attempt
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Header spoofing detected: X-User-Id does not match token claim"));
    }

    // ==========================================
    // Phase 12 - Workflow Ownership Isolation Tests
    // ==========================================

    @Test
    @DisplayName("9. User A creates workflow: assigned User A ownership and persisted")
    void shouldAssignOwnershipToUserAOnCreation() throws Exception {
        String tokenA = generateToken(USER_A_EMAIL, USER_A_ID, 3600000L, TEST_SECRET);

        CreateWorkflowRequest request = new CreateWorkflowRequest();
        request.setName("Security Pipeline");
        request.setGoal("Audit workflow ownership boundary");

        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> {
            Workflow w = inv.getArgument(0);
            return Workflow.builder()
                    .id(501L)
                    .name(w.getName())
                    .goal(w.getGoal())
                    .status(w.getStatus())
                    .userId(w.getUserId())
                    .build();
        });

        mockMvc.perform(post("/api/v1/workflows")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(501))
                .andExpect(jsonPath("$.userId").value(USER_A_ID));

        verify(workflowRepository).save(argThat(w -> USER_A_ID.equals(w.getUserId())));
    }

    @Test
    @DisplayName("10. User B cannot access User A's workflow (returns 404 to avoid enumeration)")
    void userBCannotAccessUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);

        // When queried by ID 1 and USER_B_ID, repository returns empty (owned by User A)
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/workflows/1")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Workflow not found: 1"));
    }

    @Test
    @DisplayName("11. User B cannot start User A's workflow (returns 404)")
    void userBCannotStartUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/workflows/1/start")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        verify(workflowRepository, never()).save(any());
        verify(executionRequestProducer, never()).publishExecutionRequest(any());
    }

    @Test
    @DisplayName("12. User B cannot pause User A's workflow (returns 404)")
    void userBCannotPauseUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/workflows/1/pause")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        verify(workflowPauseProducer, never()).publishWorkflowPaused(any());
    }

    @Test
    @DisplayName("13. User B cannot resume User A's workflow (returns 404)")
    void userBCannotResumeUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/workflows/1/resume")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        verify(workflowResumeProducer, never()).publishWorkflowResumed(any());
    }

    @Test
    @DisplayName("14. User B cannot cancel User A's workflow (returns 404)")
    void userBCannotCancelUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/workflows/1/cancel")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        verify(workflowCancellationProducer, never()).publishWorkflowCancelled(any());
    }

    @Test
    @DisplayName("15. User B cannot retry User A's workflow (returns 404)")
    void userBCannotRetryUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/workflows/1/retry")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        verify(workflowTaskRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("16. User B cannot retrieve tasks for User A's workflow (returns 404)")
    void userBCannotGetTasksForUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/workflows/1/tasks")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        verify(workflowTaskRepository, never()).findByWorkflowIdOrderByExecutionOrder(any());
    }

    @Test
    @DisplayName("17. User B cannot retrieve execution history for User A's workflow (returns 404)")
    void userBCannotGetExecutionHistoryForUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/workflows/1/executions")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        verify(workflowExecutionRepository, never()).findByWorkflowIdOrderByStartedAtDesc(any());
    }

    @Test
    @DisplayName("18. User B cannot retrieve analytics for User A's workflow (returns 404)")
    void userBCannotGetAnalyticsForUserAWorkflow() throws Exception {
        String tokenB = generateToken(USER_B_EMAIL, USER_B_ID, 3600000L, TEST_SECRET);
        when(workflowRepository.findByIdAndUserId(1L, USER_B_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/workflows/1/analytics")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("19. User A listing workflows only receives User A workflows")
    void userAListingWorkflowsReceivesOnlyUserAWorkflows() throws Exception {
        String tokenA = generateToken(USER_A_EMAIL, USER_A_ID, 3600000L, TEST_SECRET);

        Workflow workflowA = Workflow.builder()
                .id(1L)
                .name("Workflow A")
                .goal("Goal A")
                .userId(USER_A_ID)
                .build();

        when(workflowRepository.findByUserIdOrderByIdDesc(eq(USER_A_ID), any()))
                .thenReturn(new PageImpl<>(List.of(workflowA), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/workflows")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(USER_A_ID));

        verify(workflowRepository).findByUserIdOrderByIdDesc(eq(USER_A_ID), any());
    }

    // ==========================================
    // Phase 12 - Legacy Workflow Handling
    // ==========================================

    @Test
    @DisplayName("20. Legacy workflow without ownership (userId=null) is inaccessible to normal users (returns 404)")
    void legacyWorkflowInaccessibleToNormalUsers() throws Exception {
        String tokenA = generateToken(USER_A_EMAIL, USER_A_ID, 3600000L, TEST_SECRET);

        // findByIdAndUserId(999L, USER_A_ID) returns empty because legacy record has userId IS NULL
        when(workflowRepository.findByIdAndUserId(999L, USER_A_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/workflows/999")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Workflow not found: 999"));
    }

    // ==========================================
    // Phase 12 - Internal Operations Independence
    // ==========================================

    @Test
    @DisplayName("21. Internal scheduler invocation without HTTP token starts workflow successfully")
    void internalSchedulerCanStartWorkflowWithoutHttpToken() {
        // In internal background processing, UserContext is null
        UserContext.clear();

        Workflow workflow = Workflow.builder()
                .id(10L)
                .name("Scheduled Pipeline")
                .status(WorkflowStatus.CREATED)
                .userId(USER_A_ID)
                .build();

        when(workflowRepository.findById(10L)).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowTaskRepository.findByWorkflowIdOrderByExecutionOrder(10L)).thenReturn(List.of());

        Workflow started = workflowService.startWorkflow(10L);

        assertThat(started.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        verify(executionRequestProducer).publishExecutionRequest(any());
    }
}
