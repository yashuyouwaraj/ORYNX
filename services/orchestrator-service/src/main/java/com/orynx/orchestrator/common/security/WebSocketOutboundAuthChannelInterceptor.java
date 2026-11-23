package com.orynx.orchestrator.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Enforces JWT expiration on outbound STOMP MESSAGE frames per recipient session.
 * When a recipient session's JWT has expired (or the session is unauthenticated),
 * delivery to that session is suppressed and the transport session is terminated
 * without affecting other valid sessions.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketOutboundAuthChannelInterceptor implements ChannelInterceptor {

    private final WebSocketSessionRegistry sessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() != SimpMessageType.MESSAGE) {
            return message;
        }

        String sessionId = accessor.getSessionId();
        WebSocketPrincipal principal = sessionId != null
                ? sessionRegistry.getPrincipal(sessionId)
                : null;

        if (principal == null && accessor.getUser() instanceof WebSocketPrincipal headerPrincipal) {
            principal = headerPrincipal;
        }

        if (principal == null || principal.isExpired()) {
            log.info("Suppressing outbound WebSocket MESSAGE for expired/unauthenticated session={}", sessionId);
            if (sessionId != null) {
                sessionRegistry.terminateSession(sessionId);
            }
            return null;
        }

        return message;
    }
}
