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
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    private String generateToken(long userId) {
        return Jwts.builder()
                .setSubject("ws-user-" + userId + "@orynx.test")
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000L))
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
}
