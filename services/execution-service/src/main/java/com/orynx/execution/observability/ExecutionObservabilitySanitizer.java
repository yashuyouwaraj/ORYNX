package com.orynx.execution.observability;

import java.util.regex.Pattern;

/**
 * Security-focused sanitization utility for correlation identifiers, logs, and span attributes
 * in execution-service.
 */
public final class ExecutionObservabilitySanitizer {

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

    private ExecutionObservabilitySanitizer() {
    }

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
}
