package com.orynx.orchestrator.coordination;

import com.orynx.orchestrator.coordination.lock.RedisDistributedLockService;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisDistributedLockServiceUnitTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisScript<Long> releaseLockScript;
    private RedisCoordinationMetrics metrics;
    private RedisDistributedLockService lockService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        releaseLockScript = mock(RedisScript.class);
        metrics = new RedisCoordinationMetrics(new SimpleMeterRegistry());
        lockService = new RedisDistributedLockService(redisTemplate, releaseLockScript, metrics);
    }

    @Test
    @DisplayName("tryAcquire succeeds when lock key is free in Redis")
    void tryAcquireSuccess() {
        when(valueOperations.setIfAbsent(eq(RedisDistributedLockService.LOCK_KEY_PREFIX + "scheduler-leader"), eq("token-1"), eq(Duration.ofSeconds(10))))
                .thenReturn(true);

        boolean acquired = lockService.tryAcquire("scheduler-leader", "token-1", Duration.ofSeconds(10));

        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("tryAcquire fails when lock is already held by another instance")
    void tryAcquireFailsWhenAlreadyHeld() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        boolean acquired = lockService.tryAcquire("scheduler-leader", "token-2", Duration.ofSeconds(10));

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("tryAcquire fails gracefully on Redis exception")
    void tryAcquireHandlesRedisException() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Timeout"));

        boolean acquired = lockService.tryAcquire("scheduler-leader", "token-3", Duration.ofSeconds(10));

        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("release succeeds only when token matches expected owner")
    void releaseSuccessWhenTokenMatches() {
        when(redisTemplate.execute(eq(releaseLockScript), eq(List.of(RedisDistributedLockService.LOCK_KEY_PREFIX + "scheduler-leader")), eq("token-1")))
                .thenReturn(1L);

        boolean released = lockService.release("scheduler-leader", "token-1");

        assertThat(released).isTrue();
    }

    @Test
    @DisplayName("release fails when token does not match (prevents stale holder release)")
    void releaseFailsWhenTokenMismatches() {
        when(redisTemplate.execute(eq(releaseLockScript), anyList(), anyString()))
                .thenReturn(0L);

        boolean released = lockService.release("scheduler-leader", "stale-token");

        assertThat(released).isFalse();
    }

    @Test
    @DisplayName("executeWithLock runs action and releases lock automatically")
    void executeWithLockExecutesActionAndReleases() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(eq(releaseLockScript), anyList(), anyString()))
                .thenReturn(1L);

        AtomicBoolean actionExecuted = new AtomicBoolean(false);
        Optional<String> result = lockService.executeWithLock("critical-section", Duration.ofSeconds(5), () -> {
            actionExecuted.set(true);
            return "SUCCESS";
        });

        assertThat(result).contains("SUCCESS");
        assertThat(actionExecuted).isTrue();
        verify(redisTemplate).execute(eq(releaseLockScript), anyList(), anyString());
    }

    @Test
    @DisplayName("executeWithLock skips action when lock cannot be acquired")
    void executeWithLockSkipsWhenLockUnavailable() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        AtomicBoolean actionExecuted = new AtomicBoolean(false);
        Optional<String> result = lockService.executeWithLock("critical-section", Duration.ofSeconds(5), () -> {
            actionExecuted.set(true);
            return "SUCCESS";
        });

        assertThat(result).isEmpty();
        assertThat(actionExecuted).isFalse();
        verify(redisTemplate, never()).execute(any(), anyList(), anyString());
    }
}
