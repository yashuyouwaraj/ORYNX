package com.orynx.orchestrator.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active WebSocket sessions and their authenticated {@link WebSocketPrincipal}
 * by STOMP/WebSocket session ID so outbound delivery can enforce JWT expiration
 * per recipient session and actively terminate expired sessions.
 */
@Component
@Slf4j
public class WebSocketSessionRegistry {

    private final ConcurrentHashMap<String, WebSocketPrincipal> principalsBySessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> transportSessionsBySessionId = new ConcurrentHashMap<>();

    public void registerPrincipal(String sessionId, WebSocketPrincipal principal) {
        if (sessionId != null && !sessionId.isBlank() && principal != null) {
            principalsBySessionId.put(sessionId, principal);
        }
    }

    public void registerTransportSession(WebSocketSession session) {
        if (session != null && session.getId() != null) {
            transportSessionsBySessionId.put(session.getId(), session);
        }
    }

    public WebSocketPrincipal getPrincipal(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return principalsBySessionId.get(sessionId);
    }

    public void terminateSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        principalsBySessionId.remove(sessionId);
        WebSocketSession session = transportSessionsBySessionId.remove(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ex) {
                log.debug("Failed to close expired WebSocket session {}: {}", sessionId, ex.getMessage());
            }
        }
    }

    public void removeSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        principalsBySessionId.remove(sessionId);
        transportSessionsBySessionId.remove(sessionId);
    }
}
