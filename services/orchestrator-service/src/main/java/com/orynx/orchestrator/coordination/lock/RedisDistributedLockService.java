package com.orynx.orchestrator.coordination.lock;

import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Production Redis token-fenced distributed lock service.
 * Employs atomic SET NX EX for acquisition and Lua-based token-verified DEL for release,
 * preventing stale holders or expired leases from releasing locks held by new owners.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisDistributedLockService implements DistributedLockService {

    public static final String LOCK_KEY_PREFIX = "orynx:lock:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> releaseLockScript;
    private final RedisCoordinationMetrics metrics;

    @Override
    public boolean tryAcquire(String lockKey, String token, Duration leaseDuration) {
        if (lockKey == null || token == null || leaseDuration == null) {
            throw new IllegalArgumentException("lockKey, token, and leaseDuration cannot be null");
        }

        String fullKey = LOCK_KEY_PREFIX + lockKey;
        long startTime = System.currentTimeMillis();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(fullKey, token, leaseDuration);
            long duration = System.currentTimeMillis() - startTime;
            boolean success = Boolean.TRUE.equals(acquired);
            metrics.recordOperation("lock_acquire", true, duration);
            if (success) {
                log.debug("Acquired distributed lock {} with token {}", fullKey, token);
            }
            return success;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("lock_acquire", false, duration);
            log.error("Failed to acquire distributed lock {}: {}", fullKey, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean release(String lockKey, String token) {
        if (lockKey == null || token == null) {
            return false;
        }

        String fullKey = LOCK_KEY_PREFIX + lockKey;
        long startTime = System.currentTimeMillis();
        try {
            Long result = redisTemplate.execute(releaseLockScript, List.of(fullKey), token);
            long duration = System.currentTimeMillis() - startTime;
            boolean released = result != null && result > 0;
            metrics.recordOperation("lock_release", true, duration);
            if (released) {
                log.debug("Released distributed lock {} with token {}", fullKey, token);
            } else {
                log.warn("Failed to release distributed lock {}: token mismatch or lease expired", fullKey);
            }
            return released;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordOperation("lock_release", false, duration);
            log.error("Error releasing distributed lock {}: {}", fullKey, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public <T> Optional<T> executeWithLock(String lockKey, Duration leaseDuration, Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        if (!tryAcquire(lockKey, token, leaseDuration)) {
            log.info("Could not acquire lock for {}, skipping execution", lockKey);
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(action.get());
        } finally {
            release(lockKey, token);
        }
    }
}
