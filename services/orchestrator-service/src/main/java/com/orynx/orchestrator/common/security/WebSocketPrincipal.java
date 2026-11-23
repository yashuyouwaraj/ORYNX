package com.orynx.orchestrator.common.security;

import java.security.Principal;
import java.time.Instant;

/**
 * Authenticated WebSocket session principal backed by a verified JWT.
 * {@link #getName()} returns the numeric userId string so Spring's user-destination
 * routing (/user/queue/*) isolates deliveries strictly to the authenticated owner.
 */
public class WebSocketPrincipal implements Principal {

    private final Long userId;
    private final String email;
    private final Instant expiresAt;

    public WebSocketPrincipal(Long userId, String email, Instant expiresAt) {
        this.userId = userId;
        this.email = email;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
