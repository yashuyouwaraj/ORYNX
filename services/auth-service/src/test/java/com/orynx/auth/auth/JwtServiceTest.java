package com.orynx.auth.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String VALID_TEST_SECRET = "testSecretKeyForJwtSigning1234567890!@#$";
    private static final String OTHER_VALID_SECRET = "anotherTestSecretKeyForJwtSigning1234567!";
    private static final long ONE_HOUR_MS = 3600000L;

    @Test
    @DisplayName("Should generate token and validate successfully with valid configured secret")
    void shouldGenerateAndValidateTokenSuccessfully() {
        JwtService jwtService = new JwtService(VALID_TEST_SECRET, ONE_HOUR_MS);
        String email = "engineer@orynx.io";

        String token = jwtService.generateToken(email);

        assertThat(token).isNotBlank();
        assertThat(jwtService.extractEmail(token)).isEqualTo(email);
        assertThat(jwtService.isTokenValid(token, email)).isTrue();
        assertThat(jwtService.isTokenExpired(token)).isFalse();
    }

    @Test
    @DisplayName("Should reject token when validated against mismatched email")
    void shouldRejectTokenWhenEmailDoesNotMatch() {
        JwtService jwtService = new JwtService(VALID_TEST_SECRET, ONE_HOUR_MS);
        String token = jwtService.generateToken("user1@orynx.io");

        assertThat(jwtService.isTokenValid(token, "user2@orynx.io")).isFalse();
    }

    @Test
    @DisplayName("Should reject token signed with a different secret")
    void shouldRejectTokenSignedWithDifferentSecret() {
        JwtService serviceA = new JwtService(VALID_TEST_SECRET, ONE_HOUR_MS);
        JwtService serviceB = new JwtService(OTHER_VALID_SECRET, ONE_HOUR_MS);

        String tokenFromA = serviceA.generateToken("alice@orynx.io");

        assertThrows(SignatureException.class, () -> serviceB.extractEmail(tokenFromA));
        assertThrows(SignatureException.class, () -> serviceB.isTokenValid(tokenFromA, "alice@orynx.io"));
    }

    @Test
    @DisplayName("Should reject expired token")
    void shouldRejectExpiredToken() {
        // Expiration in the past (1ms) with immediate expiration
        JwtService jwtService = new JwtService(VALID_TEST_SECRET, 1L);
        String token = jwtService.generateToken("expired@orynx.io");

        // Wait brief moment to guarantee expiration
        try {
            Thread.sleep(10L);
        } catch (InterruptedException ignored) {}

        assertThrows(ExpiredJwtException.class, () -> jwtService.extractEmail(token));
        assertThrows(ExpiredJwtException.class, () -> jwtService.isTokenValid(token, "expired@orynx.io"));
    }

    @Test
    @DisplayName("Should fail fast when secret is null or blank")
    void shouldFailFastWhenSecretIsMissingOrBlank() {
        IllegalStateException nullEx = assertThrows(
                IllegalStateException.class,
                () -> new JwtService(null, ONE_HOUR_MS)
        );
        assertThat(nullEx.getMessage()).contains("must not be blank");

        IllegalStateException blankEx = assertThrows(
                IllegalStateException.class,
                () -> new JwtService("   ", ONE_HOUR_MS)
        );
        assertThat(blankEx.getMessage()).contains("must not be blank");
    }

    @Test
    @DisplayName("Should fail fast when secret is shorter than 256 bits (32 bytes)")
    void shouldFailFastWhenSecretIsTooShort() {
        String shortSecret = "too-short-key"; // 13 bytes < 32 bytes

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new JwtService(shortSecret, ONE_HOUR_MS)
        );
        assertThat(ex.getMessage()).contains("at least 256 bits");
    }

    @Test
    @DisplayName("Should fail fast when expiration is zero or negative")
    void shouldFailFastWhenExpirationIsInvalid() {
        IllegalStateException zeroEx = assertThrows(
                IllegalStateException.class,
                () -> new JwtService(VALID_TEST_SECRET, 0L)
        );
        assertThat(zeroEx.getMessage()).contains("must be positive");

        IllegalStateException negEx = assertThrows(
                IllegalStateException.class,
                () -> new JwtService(VALID_TEST_SECRET, -5000L)
        );
        assertThat(negEx.getMessage()).contains("must be positive");
    }
}
