package com.orynx.auth.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Service
public class JwtService {

    public static final int MIN_SECRET_BYTE_LENGTH = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:3600000}")
    private long expiration;

    public JwtService() {}

    public JwtService(String secret, long expiration) {
        this.secret = secret;
        this.expiration = expiration;
        validateConfiguration();
    }

    @PostConstruct
    public void validateConfiguration() {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("JWT secret configuration 'jwt.secret' must not be blank.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTE_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret must be at least 256 bits (" + MIN_SECRET_BYTE_LENGTH + " bytes) for HS-256 algorithm."
            );
        }
        if (expiration <= 0) {
            throw new IllegalStateException("JWT expiration property 'jwt.expiration' must be positive.");
        }
    }

    public String generateToken(String email) {
        return generateToken(email, null);
    }

    public String generateToken(String email, Long userId) {
        Key key = getSigningKey();

        JwtBuilder builder = Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration));

        if (userId != null) {
            builder.claim("userId", userId);
        }

        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public Long extractUserId(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

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
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public boolean isTokenValid(String token, String email) {
        String extractedEmail = extractEmail(token);
        return extractedEmail.equals(email) && !isTokenExpired(token);
    }

    public boolean isTokenExpired(String token) {
        Date expirationDate = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();

        return expirationDate.before(new Date());
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
