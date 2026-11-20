package com.orynx.orchestrator.common.security;

public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_EMAIL = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, String email) {
        CURRENT_USER_ID.set(userId);
        CURRENT_USER_EMAIL.set(email);
    }

    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static String getEmail() {
        return CURRENT_USER_EMAIL.get();
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
        CURRENT_USER_EMAIL.remove();
    }
}
