package com.orynx.orchestrator.common.security;

import com.orynx.orchestrator.workflow.Workflow;
import com.orynx.orchestrator.workflow.WorkflowEventPublisher;
import com.orynx.orchestrator.workflow.WorkflowRepository;
import com.orynx.orchestrator.workflow.WorkflowStatus;
import com.orynx.orchestrator.workflow.event.WorkflowExecutionEvent;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-stack STOMP/SockJS WebSocket integration test verifying JWT handshake authentication,
 * per-workflow subscription authorization, tenant isolation, and real-time event delivery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketStompIntegrationTest {

    @LocalServerPort
    private int port;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowEventPublisher workflowEventPublisher;

    @Autowired
    private WebSocketSessionRegistry webSocketSessionRegistry;

    private String generateToken(long userId) {
        return generateTokenWithTtl(userId, 3600000L);
    }

    private String generateTokenWithTtl(long userId, long ttlMs) {
        return Jwts.builder()
                .setSubject("ws-user-" + userId + "@orynx.test")
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    private WebSocketStompClient createStompClient() {
        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))
        );
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        return stompClient;
    }

    @Test
    @DisplayName("STOMP/SockJS rejects unauthenticated CONNECT and enforces tenant isolation for real-time updates")
    void authenticatedScopedSubscriptionAndTenantIsolation() throws Exception {
        long userA = 88001L;
        long userB = 88002L;

        Workflow wfA = workflowRepository.save(Workflow.builder()
                .name("WS-Workflow-A")
                .goal("realtime")
                .status(WorkflowStatus.CREATED)
                .userId(userA)
                .build());

        String url = "http://localhost:" + port + "/ws";
        WebSocketStompClient clientA = createStompClient();
        WebSocketStompClient clientB = createStompClient();
        WebSocketStompClient unauthClient = createStompClient();

        try {
            // 1. Unauthenticated CONNECT fails
            assertThatThrownBy(() -> unauthClient.connectAsync(
                    url,
                    new WebSocketHttpHeaders(),
                    new StompHeaders(),
                    new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);

            // 2. User A connects with valid JWT
            StompHeaders headersA = new StompHeaders();
            headersA.add("Authorization", "Bearer " + generateToken(userA));
            StompSession sessionA = clientA.connectAsync(
                    url, new WebSocketHttpHeaders(), headersA, new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            BlockingQueue<WorkflowExecutionEvent> receivedA = new LinkedBlockingQueue<>();
            sessionA.subscribe("/topic/workflows/" + wfA.getId(), new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return WorkflowExecutionEvent.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedA.add((WorkflowExecutionEvent) payload);
                }
            });

            // 3. User B connects with valid JWT and subscribes to their own /user/queue/workflows
            //    and attempts unauthorized subscribe to User A's workflow topic
            StompHeaders headersB = new StompHeaders();
            headersB.add("Authorization", "Bearer " + generateToken(userB));
            StompSession sessionB = clientB.connectAsync(
                    url, new WebSocketHttpHeaders(), headersB, new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            BlockingQueue<WorkflowExecutionEvent> receivedB = new LinkedBlockingQueue<>();
            sessionB.subscribe("/user/queue/workflows", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return WorkflowExecutionEvent.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedB.add((WorkflowExecutionEvent) payload);
                }
            });
            // Unauthorized subscription attempt by User B to User A's workflow
            sessionB.subscribe("/topic/workflows/" + wfA.getId(), new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return WorkflowExecutionEvent.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedB.add((WorkflowExecutionEvent) payload);
                }
            });

            Thread.sleep(300);

            // 4. Publish User A's workflow update
            workflowEventPublisher.publishWorkflowUpdate(WorkflowExecutionEvent.builder()
                    .workflowId(wfA.getId())
                    .workflowName(wfA.getName())
                    .status("RUNNING")
                    .userId(userA)
                    .generation(1L)
                    .build());

            WorkflowExecutionEvent deliveredToA = receivedA.poll(5, TimeUnit.SECONDS);
            assertThat(deliveredToA).isNotNull();
            assertThat(deliveredToA.getWorkflowId()).isEqualTo(wfA.getId());
            assertThat(deliveredToA.getStatus()).isEqualTo("RUNNING");

            // User B must never receive User A's event
            assertThat(receivedB.poll(500, TimeUnit.MILLISECONDS)).isNull();

            sessionA.disconnect();
            if (sessionB.isConnected()) {
                sessionB.disconnect();
            }
        } finally {
            clientA.stop();
            clientB.stop();
            unauthClient.stop();
            workflowRepository.deleteById(wfA.getId());
        }
    }

    @Test
    @DisplayName("Cross-tenant STOMP SEND spoofing is rejected and expired sessions stop receiving outbound messages")
    void sendSpoofingRejectedAndExpiredSessionTerminated() throws Exception {
        long userA = 88003L;
        long userB = 88004L;

        Workflow wfA = workflowRepository.save(Workflow.builder()
                .name("WS-Workflow-Spoof-Target")
                .goal("realtime")
                .status(WorkflowStatus.CREATED)
                .userId(userA)
                .build());

        String url = "http://localhost:" + port + "/ws";
        WebSocketStompClient clientA = createStompClient();
        WebSocketStompClient clientB = createStompClient();
        WebSocketStompClient clientAExpired = createStompClient();

        try {
            StompHeaders headersA = new StompHeaders();
            headersA.add("Authorization", "Bearer " + generateToken(userA));
            StompSession sessionA = clientA.connectAsync(
                    url, new WebSocketHttpHeaders(), headersA, new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            BlockingQueue<WorkflowExecutionEvent> receivedA = new LinkedBlockingQueue<>();
            sessionA.subscribe("/topic/workflows/" + wfA.getId(), new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return WorkflowExecutionEvent.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedA.add((WorkflowExecutionEvent) payload);
                }
            });

            // Session B connects and attempts forged SEND to User A's workflow topic
            CountDownLatch sessionBErrorLatch = new CountDownLatch(1);
            AtomicReference<String> sessionBErrorMessage = new AtomicReference<>();
            StompHeaders headersB = new StompHeaders();
            headersB.add("Authorization", "Bearer " + generateToken(userB));
            StompSession sessionB = clientB.connectAsync(
                    url,
                    new WebSocketHttpHeaders(),
                    headersB,
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            sessionBErrorMessage.compareAndSet(null, headers.getFirst("message"));
                            sessionBErrorLatch.countDown();
                        }

                        @Override
                        public void handleTransportError(StompSession session, Throwable exception) {
                            sessionBErrorMessage.compareAndSet(null, exception.getMessage());
                            sessionBErrorLatch.countDown();
                        }
                    }
            ).get(5, TimeUnit.SECONDS);

            Thread.sleep(200);

            sessionB.send("/topic/workflows/" + wfA.getId(), WorkflowExecutionEvent.builder()
                    .workflowId(wfA.getId())
                    .workflowName("Forged")
                    .status("COMPLETED")
                    .build());

            assertThat(sessionBErrorLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(receivedA.poll(300, TimeUnit.MILLISECONDS)).isNull();

            // Also verify a second Session A whose JWT expires stops receiving outbound deliveries
            StompHeaders headersA2 = new StompHeaders();
            headersA2.add("Authorization", "Bearer " + generateTokenWithTtl(userA, 1200L));
            StompSession sessionAExpired = clientAExpired.connectAsync(
                    url, new WebSocketHttpHeaders(), headersA2, new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);
            BlockingQueue<WorkflowExecutionEvent> receivedAExpired = new LinkedBlockingQueue<>();
            sessionAExpired.subscribe("/topic/workflows/" + wfA.getId(), new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return WorkflowExecutionEvent.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedAExpired.add((WorkflowExecutionEvent) payload);
                }
            });

            // Wait until the 1200ms JWT has expired naturally
            Thread.sleep(1500);

            // Legitimate server-originated update reaches valid sessionA but NOT expired sessionAExpired
            workflowEventPublisher.publishWorkflowUpdate(WorkflowExecutionEvent.builder()
                    .workflowId(wfA.getId())
                    .workflowName(wfA.getName())
                    .status("RUNNING")
                    .userId(userA)
                    .generation(1L)
                    .build());

            WorkflowExecutionEvent legitimate = receivedA.poll(5, TimeUnit.SECONDS);
            assertThat(legitimate).isNotNull();
            assertThat(legitimate.getStatus()).isEqualTo("RUNNING");
            assertThat(receivedAExpired.poll(400, TimeUnit.MILLISECONDS)).isNull();

            sessionA.disconnect();
            if (sessionB.isConnected()) {
                sessionB.disconnect();
            }
            if (sessionAExpired.isConnected()) {
                sessionAExpired.disconnect();
            }
        } finally {
            clientA.stop();
            clientB.stop();
            clientAExpired.stop();
            workflowRepository.deleteById(wfA.getId());
        }
    }
}
