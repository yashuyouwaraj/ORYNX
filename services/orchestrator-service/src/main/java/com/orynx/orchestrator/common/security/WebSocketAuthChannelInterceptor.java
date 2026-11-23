package com.orynx.orchestrator.common.security;

import com.orynx.orchestrator.workflow.WorkflowRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces JWT authentication on STOMP CONNECT and workflow-ownership authorization
 * on STOMP SUBSCRIBE frames. Global broadcast topics (/topic/workflows, /topic/tasks)
 * are rejected so tenants can only subscribe to /user/queue/* or their own
 * /topic/workflows/{workflowId}[/tasks] channels.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern WORKFLOW_TOPIC_PATTERN =
            Pattern.compile("^/topic/workflows/(\\d+)(?:/tasks)?$");

    private final OrchestratorJwtService jwtService;
    private final WorkflowRepository workflowRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            requireValidPrincipal(accessor);
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String authHeader = firstNativeHeader(accessor, "Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            authHeader = firstNativeHeader(accessor, "authorization");
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new MessageDeliveryException("Missing or invalid WebSocket Authorization header");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw new MessageDeliveryException("Empty WebSocket bearer token");
        }

        try {
            Claims claims = jwtService.parseAndValidateToken(token);
            Long userId = extractUserId(claims);
            String email = claims.getSubject();
            Date exp = claims.getExpiration();
            Instant expiresAt = exp != null ? exp.toInstant() : null;
            accessor.setUser(new WebSocketPrincipal(userId, email, expiresAt));
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Rejected WebSocket CONNECT due to invalid/expired JWT: {}", ex.getMessage());
            throw new MessageDeliveryException("Invalid or expired WebSocket token");
        }
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        WebSocketPrincipal principal = requireValidPrincipal(accessor);
        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            throw new MessageDeliveryException("Subscription destination is required");
        }

        if ("/user/queue/workflows".equals(destination) || "/user/queue/tasks".equals(destination)) {
            return;
        }

        Matcher matcher = WORKFLOW_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            log.warn("Rejected unauthorized WebSocket subscription to destination={} for userId={}",
                    destination, principal.getUserId());
            throw new MessageDeliveryException("Subscription destination not authorized");
        }

        Long workflowId;
        try {
            workflowId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw new MessageDeliveryException("Invalid workflow subscription destination");
        }

        boolean ownsWorkflow = workflowRepository.findByIdAndUserId(workflowId, principal.getUserId()).isPresent();
        if (!ownsWorkflow) {
            log.warn("Rejected WebSocket subscription to workflowId={} for userId={}",
                    workflowId, principal.getUserId());
            throw new MessageDeliveryException("Workflow subscription not authorized");
        }
    }

    private WebSocketPrincipal requireValidPrincipal(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof WebSocketPrincipal principal)) {
            throw new MessageDeliveryException("Unauthenticated WebSocket session");
        }
        if (principal.isExpired()) {
            throw new MessageDeliveryException("WebSocket authentication expired");
        }
        return principal;
    }

    private Long extractUserId(Claims claims) {
        Object claim = claims.get("userId");
        if (claim == null) {
            throw new IllegalArgumentException("Token missing required userId claim");
        }
        if (claim instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(claim.toString().trim());
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
