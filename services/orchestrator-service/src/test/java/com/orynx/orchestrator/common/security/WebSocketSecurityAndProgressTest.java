package com.orynx.orchestrator.common.security;

import com.orynx.orchestrator.execution.ExecutionEventConsumer;
import com.orynx.orchestrator.execution.WorkflowExecution;
import com.orynx.orchestrator.execution.WorkflowExecutionRepository;
import com.orynx.orchestrator.execution.event.WorkflowCompletedEvent;
import com.orynx.orchestrator.workflow.*;
import com.orynx.orchestrator.workflow.event.ExecutionEvent;
import com.orynx.orchestrator.workflow.event.ExecutionEventRepository;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import com.orynx.orchestrator.workflow.task.TaskEventConsumer;
import com.orynx.orchestrator.workflow.task.WorkflowTaskRepository;
import com.orynx.orchestrator.workflow.task.dto.TaskExecutionEvent;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketSecurityAndProgressTest {

    private static final String SECRET = "test-jwt-secret-key-32-chars-long-123456";

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ExecutionEventRepository executionEventRepository;
    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock
    private WorkflowTaskRepository workflowTaskRepository;
    @Mock
    private MessageChannel messageChannel;

    private OrchestratorJwtService jwtService;
    private WebSocketAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtService = new OrchestratorJwtService(SECRET);
        interceptor = new WebSocketAuthChannelInterceptor(jwtService, workflowRepository);
    }

    private String createToken(Long userId, String email, long ttlMs, String secret) {
        var builder = Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256);
        if (userId != null) {
            builder.claim("userId", userId);
        }
        return builder.compact();
    }

    private Message<?> stompMessage(StompCommand command, String authHeader, String destination, WebSocketPrincipal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (authHeader != null) {
            accessor.addNativeHeader("Authorization", authHeader);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("STOMP CONNECT rejects missing, malformed, wrong-secret, expired, and userId-less tokens")
    void connectRejectsInvalidTokens() {
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, null, null), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);

        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, "Basic abc", null, null), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);

        String wrongSecret = createToken(10L, "a@b.com", 60000L, "different-jwt-secret-key-32-chars-long");
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, "Bearer " + wrongSecret, null, null), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);

        String expired = createToken(10L, "a@b.com", -5000L, SECRET);
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, "Bearer " + expired, null, null), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);

        String noUserId = createToken(null, "a@b.com", 60000L, SECRET);
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, "Bearer " + noUserId, null, null), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("STOMP CONNECT authenticates valid JWT and binds WebSocketPrincipal with userId name")
    void connectAuthenticatesValidToken() {
        String token = createToken(42L, "owner@orynx.io", 60000L, SECRET);
        Message<?> result = interceptor.preSend(
                stompMessage(StompCommand.CONNECT, "Bearer " + token, null, null),
                messageChannel
        );
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isInstanceOf(WebSocketPrincipal.class);
        WebSocketPrincipal principal = (WebSocketPrincipal) accessor.getUser();
        assertThat(principal.getUserId()).isEqualTo(42L);
        assertThat(principal.getName()).isEqualTo("42");
    }

    @Test
    @DisplayName("STOMP SUBSCRIBE blocks global topics, cross-user workflows, non-existent IDs, and expired sessions")
    void subscribeEnforcesOwnershipAndBlocksGlobalTopics() {
        WebSocketPrincipal userA = new WebSocketPrincipal(101L, "a@orynx.io", Instant.now().plusSeconds(300));
        WebSocketPrincipal expiredUser = new WebSocketPrincipal(101L, "a@orynx.io", Instant.now().minusSeconds(10));

        // Global broadcast channels rejected
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.SUBSCRIBE, null, "/topic/workflows", userA), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.SUBSCRIBE, null, "/topic/tasks", userA), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);

        // User-scoped queues allowed for non-expired user
        interceptor.preSend(stompMessage(StompCommand.SUBSCRIBE, null, "/user/queue/workflows", userA), messageChannel);
        interceptor.preSend(stompMessage(StompCommand.SUBSCRIBE, null, "/user/queue/tasks", userA), messageChannel);

        // Expired session rejected on SUBSCRIBE and SEND
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.SUBSCRIBE, null, "/user/queue/workflows", expiredUser), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.SEND, null, "/app/ping", expiredUser), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);

        // Owned workflow allowed; unowned or non-existent workflow rejected identically
        when(workflowRepository.findByIdAndUserId(55L, 101L))
                .thenReturn(Optional.of(Workflow.builder().id(55L).userId(101L).build()));
        when(workflowRepository.findByIdAndUserId(99L, 101L)).thenReturn(Optional.empty());

        interceptor.preSend(stompMessage(StompCommand.SUBSCRIBE, null, "/topic/workflows/55", userA), messageChannel);
        interceptor.preSend(stompMessage(StompCommand.SUBSCRIBE, null, "/topic/workflows/55/tasks", userA), messageChannel);

        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.SUBSCRIBE, null, "/topic/workflows/99", userA), messageChannel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Workflow subscription not authorized");

        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.SUBSCRIBE, null, "/topic/workflows/not-a-number", userA), messageChannel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("WorkflowEventPublisher, TaskEventConsumer, and ExecutionEventConsumer route scoped events and suppress stale/duplicates")
    void scopedEventPublicationAndStaleSuppression() {
        WorkflowEventPublisher publisher = new WorkflowEventPublisher(messagingTemplate, workflowRepository);
        WorkflowExecutionEvent wfEvent = WorkflowExecutionEvent.builder()
                .workflowId(7L)
                .workflowName("WF-7")
                .status("RUNNING")
                .userId(101L)
                .generation(2L)
                .build();
        publisher.publishWorkflowUpdate(wfEvent);

        verify(messagingTemplate).convertAndSend("/topic/workflows/7", wfEvent);
        verify(messagingTemplate).convertAndSendToUser("101", "/queue/workflows", wfEvent);

        // TaskEventConsumer: valid event publishes to scoped channels; stale/duplicate suppressed
        TaskEventConsumer taskConsumer = new TaskEventConsumer(
                messagingTemplate, executionEventRepository, workflowExecutionRepository, workflowRepository);

        WorkflowExecution activeExec = WorkflowExecution.builder()
                .id(1L)
                .status(WorkflowStatus.RUNNING)
                .executionGeneration(2L)
                .build();
        when(workflowExecutionRepository.findFirstByWorkflowIdOrderByStartedAtDesc(7L))
                .thenReturn(Optional.of(activeExec));

        // Stale generation (1 < 2) must be suppressed
        taskConsumer.consume(TaskExecutionEvent.builder()
                .workflowId(7L).taskId(11L).taskName("T1").status("RUNNING").generation(1L).eventId("ev-stale").build());
        verify(executionEventRepository, never()).save(any());

        // Valid generation (2) publishes to scoped task destinations
        when(executionEventRepository.existsByWorkflowIdAndEventId(7L, "ev-ok")).thenReturn(false);
        when(executionEventRepository.findFirstByWorkflowIdAndTaskIdOrderByIdDesc(7L, 11L)).thenReturn(Optional.empty());
        when(workflowRepository.findById(7L)).thenReturn(Optional.of(Workflow.builder().id(7L).userId(101L).build()));

        TaskExecutionEvent validTaskEvent = TaskExecutionEvent.builder()
                .workflowId(7L).taskId(11L).taskName("T1").status("COMPLETED").generation(2L).eventId("ev-ok").build();
        taskConsumer.consume(validTaskEvent);

        verify(executionEventRepository).save(any(ExecutionEvent.class));
        verify(messagingTemplate).convertAndSend("/topic/workflows/7/tasks", validTaskEvent);
        verify(messagingTemplate).convertAndSendToUser("101", "/queue/tasks", validTaskEvent);

        // ExecutionEventConsumer publishes workflow completion to WorkflowEventPublisher
        Workflow wf = Workflow.builder().id(7L).name("WF-7").status(WorkflowStatus.RUNNING).userId(101L).build();
        when(workflowRepository.findById(7L)).thenReturn(Optional.of(wf));
        WorkflowEventPublisher mockPublisher = mock(WorkflowEventPublisher.class);
        ExecutionEventConsumer execConsumer = new ExecutionEventConsumer(
                workflowRepository, workflowTaskRepository, workflowExecutionRepository, mockPublisher);

        execConsumer.consumeWorkflowCompleted(new WorkflowCompletedEvent(7L, "WF-7", true, 2L, "wf-comp-1"));
        verify(mockPublisher).publishWorkflowUpdate(argThat(ev ->
                ev.getWorkflowId().equals(7L)
                        && "COMPLETED".equals(ev.getStatus())
                        && Long.valueOf(101L).equals(ev.getUserId())
                        && Long.valueOf(2L).equals(ev.getGeneration())));
    }

    @Test
    @DisplayName("STOMP SEND is unconditionally rejected across /topic, /queue, /user, and /app destinations")
    void sendIsUnconditionallyRejectedAcrossAllDestinations() {
        WebSocketPrincipal user = new WebSocketPrincipal(101L, "a@orynx.io", Instant.now().plusSeconds(300));
        for (String dest : new String[]{
                "/topic/workflows/55",
                "/topic/workflows/55/tasks",
                "/app/anything",
                "/queue/tasks",
                "/user/queue/workflows"
        }) {
            assertThatThrownBy(() -> interceptor.preSend(
                    stompMessage(StompCommand.SEND, null, dest, user), messageChannel))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("Client STOMP SEND is not permitted");
        }
    }

    @Test
    @DisplayName("Outbound interceptor suppresses MESSAGE delivery and terminates expired sessions without affecting valid sessions")
    void outboundInterceptorSuppressesAndTerminatesExpiredSessionsOnly() throws Exception {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        WebSocketOutboundAuthChannelInterceptor outbound = new WebSocketOutboundAuthChannelInterceptor(registry);

        WebSocketPrincipal validPrincipal = new WebSocketPrincipal(101L, "a@orynx.io", Instant.now().plusSeconds(300));
        WebSocketPrincipal expiredPrincipal = new WebSocketPrincipal(101L, "a@orynx.io", Instant.now().minusSeconds(5));

        WebSocketSession validWs = mock(WebSocketSession.class);
        when(validWs.getId()).thenReturn("sess-valid");
        WebSocketSession expiredWs = mock(WebSocketSession.class);
        when(expiredWs.getId()).thenReturn("sess-expired");
        when(expiredWs.isOpen()).thenReturn(true);

        registry.registerTransportSession(validWs);
        registry.registerTransportSession(expiredWs);
        registry.registerPrincipal("sess-valid", validPrincipal);
        registry.registerPrincipal("sess-expired", expiredPrincipal);

        // Build outbound MESSAGE for valid session (topic and user-queue)
        SimpMessageHeaderAccessor validTopicAcc = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        validTopicAcc.setSessionId("sess-valid");
        validTopicAcc.setDestination("/topic/workflows/55");
        Message<byte[]> validTopicMsg = MessageBuilder.createMessage(new byte[]{1}, validTopicAcc.getMessageHeaders());
        assertThat(outbound.preSend(validTopicMsg, messageChannel)).isNotNull();

        // Build outbound MESSAGE for expired session (topic and user-queue)
        SimpMessageHeaderAccessor expiredTopicAcc = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        expiredTopicAcc.setSessionId("sess-expired");
        expiredTopicAcc.setDestination("/topic/workflows/55");
        Message<byte[]> expiredTopicMsg = MessageBuilder.createMessage(new byte[]{1}, expiredTopicAcc.getMessageHeaders());
        assertThat(outbound.preSend(expiredTopicMsg, messageChannel)).isNull();

        verify(expiredWs).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.getPrincipal("sess-expired")).isNull();

        // Valid session for the exact same user continues receiving messages unaffected
        SimpMessageHeaderAccessor validUserAcc = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        validUserAcc.setSessionId("sess-valid");
        validUserAcc.setDestination("/user/queue/workflows");
        Message<byte[]> validUserMsg = MessageBuilder.createMessage(new byte[]{1}, validUserAcc.getMessageHeaders());
        assertThat(outbound.preSend(validUserMsg, messageChannel)).isNotNull();
        verify(validWs, never()).close(any());

        // Expired user-queue message without open transport is also suppressed
        registry.registerPrincipal("sess-expired-2", expiredPrincipal);
        SimpMessageHeaderAccessor expiredUserAcc = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        expiredUserAcc.setSessionId("sess-expired-2");
        expiredUserAcc.setDestination("/user/queue/workflows");
        Message<byte[]> expiredUserMsg = MessageBuilder.createMessage(new byte[]{1}, expiredUserAcc.getMessageHeaders());
        assertThat(outbound.preSend(expiredUserMsg, messageChannel)).isNull();
    }
}
