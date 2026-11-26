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
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)([A-Za-z0-9_.-]*authorization\\s*[:=]\\s*)(?:(?:Bearer|Basic|Digest|Token|ApiKey)\\s+)?[^\\s,;\"'&\\]})]+"
    );
    private static final Pattern STANDALONE_AUTH_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9\\-._~+/]{6,}={0,2}(\\s|$|[,;\"'&\\]})])"
    );
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern SECRET_DOUBLE_QUOTED_KV_PATTERN = Pattern.compile(
            "(?i)(\"?[A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|authorization|jwt|credentials?)(?:[_-]?value)?\"?\\s*[:=]\\s*\")((?:\\\\.|[^\"\\\\\\r\\n])+)(\")"
    );
    private static final Pattern SECRET_SINGLE_QUOTED_KV_PATTERN = Pattern.compile(
            "(?i)('?[A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|authorization|jwt|credentials?)(?:[_-]?value)?'?\\s*[:=]\\s*')((?:\\\\.|[^'\\\\\\r\\n])+)(')"
    );
    private static final Pattern SECRET_KV_PATTERN = Pattern.compile(
            "(?i)([A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|jwt|credentials?)(?:[_-]?value)?\\s*[:=]\\s*)[^\\s,;\"'&\\]})]+"
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
        sanitized = URL_CREDENTIAL_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$3");
        sanitized = SECRET_DOUBLE_QUOTED_KV_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$3");
        sanitized = SECRET_SINGLE_QUOTED_KV_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$3");
        sanitized = AUTH_HEADER_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = STANDALONE_AUTH_PATTERN.matcher(sanitized).replaceAll("$1 [REDACTED]$2");
        sanitized = SECRET_KV_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = JWT_PATTERN.matcher(sanitized).replaceAll("[REDACTED_JWT]");
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
            if (name.contains("StaleWorker")) {
                return "FENCING";
            }
            if (name.contains("WorkerQueueFull") || curr instanceof java.util.concurrent.RejectedExecutionException) {
                return "CAPACITY";
            }
            if (name.contains("Deserialization") || name.contains("MessageConversion")
                    || name.contains("Conversion") || name.contains("JsonProcessing")) {
                return "DESERIALIZATION";
            }
            if (name.contains("Validation") || curr instanceof IllegalArgumentException) {
                return "VALIDATION";
            }
            if (name.contains("Timeout")) {
                return "TIMEOUT";
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
