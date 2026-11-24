package com.orynx.orchestrator.coordination;

import com.orynx.orchestrator.coordination.config.RedisCoordinationConfig;
import com.orynx.orchestrator.coordination.lock.DistributedLockService;
import com.orynx.orchestrator.coordination.lock.RedisDistributedLockService;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private DistributedLockService lockService;

    private static final String LOCK_NAME = "integration-test-resource";

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", 6379);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        RedisCoordinationConfig coordinationConfig = new RedisCoordinationConfig();
        RedisScript<Long> releaseLockScript = coordinationConfig.releaseLockScript();
        RedisCoordinationMetrics metrics = new RedisCoordinationMetrics(new SimpleMeterRegistry());

        lockService = new RedisDistributedLockService(redisTemplate, releaseLockScript, metrics);

        // Clean up test lock
        redisTemplate.delete("orynx:lock:" + LOCK_NAME);
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            redisTemplate.delete("orynx:lock:" + LOCK_NAME);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("Successfully acquire lock and release with valid token")
    void acquireAndReleaseLock() {
        String token = UUID.randomUUID().toString();
        boolean acquired = lockService.tryAcquire(LOCK_NAME, token, Duration.ofSeconds(10));
        assertThat(acquired).isTrue();

        // Verify key exists in Redis with expected value
        String storedToken = redisTemplate.opsForValue().get("orynx:lock:" + LOCK_NAME);
        assertThat(storedToken).isEqualTo(token);

        boolean released = lockService.release(LOCK_NAME, token);
        assertThat(released).isTrue();
        assertThat(redisTemplate.hasKey("orynx:lock:" + LOCK_NAME)).isFalse();
    }

    @Test
    @DisplayName("Mutual exclusion: second attempt fails while lock is held")
    void mutualExclusionEnforced() {
        String token1 = UUID.randomUUID().toString();
        String token2 = UUID.randomUUID().toString();

        boolean acquired1 = lockService.tryAcquire(LOCK_NAME, token1, Duration.ofSeconds(10));
        assertThat(acquired1).isTrue();

        boolean acquired2 = lockService.tryAcquire(LOCK_NAME, token2, Duration.ofSeconds(10));
        assertThat(acquired2).isFalse();

        // Release first lock
        assertThat(lockService.release(LOCK_NAME, token1)).isTrue();

        // Second lock acquisition should now succeed
        boolean acquired3 = lockService.tryAcquire(LOCK_NAME, token2, Duration.ofSeconds(10));
        assertThat(acquired3).isTrue();
        assertThat(lockService.release(LOCK_NAME, token2)).isTrue();
    }

    @Test
    @DisplayName("Releasing with mismatched token fails and preserves lock")
    void releaseWithInvalidTokenFails() {
        String token = UUID.randomUUID().toString();
        boolean acquired = lockService.tryAcquire(LOCK_NAME, token, Duration.ofSeconds(10));
        assertThat(acquired).isTrue();

        boolean released = lockService.release(LOCK_NAME, "wrong-token-abc");
        assertThat(released).isFalse();
        assertThat(redisTemplate.hasKey("orynx:lock:" + LOCK_NAME)).isTrue();

        // Clean up with correct token
        assertThat(lockService.release(LOCK_NAME, token)).isTrue();
    }

    @Test
    @DisplayName("executeWithLock executes supplier and automatically releases lock")
    void executeWithLockExecutesAndReleases() {
        Optional<String> result = lockService.executeWithLock(LOCK_NAME, Duration.ofSeconds(10), () -> "success-result");

        assertThat(result).contains("success-result");
        // Lock should be released automatically
        assertThat(redisTemplate.hasKey("orynx:lock:" + LOCK_NAME)).isFalse();
    }
}
