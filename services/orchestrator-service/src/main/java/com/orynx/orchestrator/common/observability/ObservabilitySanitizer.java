package com.orynx.orchestrator.common.observability;

import java.util.regex.Pattern;

/**
 * Security-focused sanitization utility for correlation identifiers, logs, and span attributes.
 * Prevents log forging (CRLF injection), cardinality abuse, and accidental secret exposure.
 */
public final class ObservabilitySanitizer {

    public static final int MAX_CORRELATION_ID_LENGTH = 128;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern SECRET_KV_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|authorization|jwt|client[_-]?secret|private[_-]?key|access[_-]?key)\\b(\\s*[:=]\\s*)([^\\s,;\"'&\\]})]+)"
    );
    private static final Pattern URL_CREDENTIAL_PATTERN = Pattern.compile("(://[^\\s:/@]+:)([^\\s@]+)(@)");
    private static final Pattern PEM_KEY_PATTERN = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private ObservabilitySanitizer() {
    }

    /**
     * Validates and sanitizes an incoming correlation/request identifier.
     * Returns null if null, blank, exceeds 128 chars, or contains unsafe characters (such as CR/LF).
     */
    public static String sanitizeCorrelationId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_CORRELATION_ID_LENGTH) {
            return null;
        }
        return SAFE_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * Redacts credentials, Bearer tokens, JWTs, API keys, private keys, and URL passwords from text.
     */
    public static String redactSensitiveText(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String sanitized = PEM_KEY_PATTERN.matcher(input).replaceAll("[REDACTED_PRIVATE_KEY]");
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        sanitized = JWT_PATTERN.matcher(sanitized).replaceAll("[REDACTED_JWT]");
        sanitized = SECRET_KV_PATTERN.matcher(sanitized).replaceAll("$1$2[REDACTED]");
        sanitized = URL_CREDENTIAL_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$3");
        return sanitized.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Produces a bounded, redacted error message safe for structured logs and trace span attributes.
     */
    public static String safeErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String msg = throwable.getMessage();
        if (msg == null || msg.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String redacted = redactSensitiveText(msg);
        if (redacted.length() > MAX_ERROR_MESSAGE_LENGTH) {
            return redacted.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "...";
        }
        return redacted;
    }

    /**
     * Classifies an exception into a bounded, low-cardinality operational error category.
     * Never returns exception messages or unbounded user input.
     */
    public static String classifyErrorCategory(Throwable throwable) {
        if (throwable == null) {
            return "INTERNAL";
        }
        Throwable curr = throwable;
        for (int depth = 0; curr != null && depth < 6; depth++, curr = curr.getCause()) {
            String name = curr.getClass().getSimpleName();
            if (name.contains("Deserialization") || name.contains("MessageConversion")
                    || name.contains("Conversion") || name.contains("JsonProcessing")
                    || name.contains("HttpMessageNotReadable")) {
                return "DESERIALIZATION";
            }
            if (name.contains("NotFound") || curr instanceof java.util.NoSuchElementException) {
                return "NOT_FOUND";
            }
            if (name.contains("Validation") || name.contains("ConstraintViolation")
                    || curr instanceof IllegalArgumentException) {
                return "VALIDATION";
            }
            if (name.contains("Timeout")) {
                return "TIMEOUT";
            }
            if (name.contains("AccessDenied") || name.contains("Authentication")
                    || curr instanceof SecurityException) {
                return "SECURITY";
            }
            if (name.contains("Redis") || name.contains("Kafka")
                    || name.contains("DataAccess") || name.contains("SQL")
                    || curr instanceof java.io.IOException) {
                return "INFRASTRUCTURE";
            }
            if (curr instanceof IllegalStateException) {
                return "STATE_CONFLICT";
            }
        }
        return "INTERNAL";
    }
}
