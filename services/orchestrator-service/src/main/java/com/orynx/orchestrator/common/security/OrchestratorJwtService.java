package com.orynx.orchestrator.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;

@Service
public class OrchestratorJwtService {

    @Value("${jwt.secret:}")
    private String secret;

    public OrchestratorJwtService() {}

    public OrchestratorJwtService(String secret) {
        this.secret = secret;
    }

    public Claims parseAndValidateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        Key key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Long extractUserId(String token) {
        Claims claims = parseAndValidateToken(token);
        Object userId = claims.get("userId");
        if (userId instanceof Number number) {
            return number.longValue();
        } else if (userId != null) {
            try {
                return Long.parseLong(userId.toString());
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public String extractEmail(String token) {
        Claims claims = parseAndValidateToken(token);
        return claims.getSubject();
    }
}
