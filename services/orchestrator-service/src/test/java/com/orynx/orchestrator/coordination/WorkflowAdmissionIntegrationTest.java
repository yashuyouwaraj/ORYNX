package com.orynx.orchestrator.coordination;

import com.orynx.orchestrator.coordination.admission.AdmissionDecision;
import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.coordination.admission.RedisWorkflowAdmissionController;
import com.orynx.orchestrator.coordination.admission.WorkflowAdmissionException;
import com.orynx.orchestrator.coordination.config.RedisCoordinationConfig;
import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Redis 7 integration and adversarial concurrency tests for distributed workflow admission control.
 * Connects to Redis on localhost:6379 (orynx-audit-redis) and verifies atomic limits,
 * user quota isolation, multi-threaded admission races, idempotency, and clean release.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowAdmissionIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisScript<List> admitScript;
    private static RedisScript<List> releaseScript;
    private static RedisCoordinationMetrics metrics;

    private RedisCoordinationProperties properties;
    private RedisWorkflowAdmissionController controller;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void initRedis() {
        String host = System.getenv().getOrDefault("SPRING_DATA_REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("SPRING_DATA_REDIS_PORT", "6379"));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        DefaultRedisScript<List> admit = new DefaultRedisScript<>();
        admit.setLocation(new ClassPathResource("lua/admit_workflow.lua"));
        admit.setResultType(List.class);
        admitScript = admit;

        DefaultRedisScript<List> release = new DefaultRedisScript<>();
        release.setLocation(new ClassPathResource("lua/release_workflow.lua"));
        release.setResultType(List.class);
        releaseScript = release;

        metrics = new RedisCoordinationMetrics(new SimpleMeterRegistry());
    }

    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        cleanRedisKeys();

        properties = new RedisCoordinationProperties();
        properties.setEnabled(true);
        properties.setMaxGlobalConcurrentWorkflows(10);
        properties.setMaxUserConcurrentWorkflows(3);
        properties.setWorkflowLeaseTtlSeconds(3600);
        properties.setRetryAfterSeconds(5);
        properties.setFailurePolicy(AdmissionFailurePolicy.FAIL_CLOSED);

        controller = new RedisWorkflowAdmissionController(
                redisTemplate,
                admitScript,
                releaseScript,
                properties,
                metrics
        );
    }

    @AfterEach
    void tearDown() {
        cleanRedisKeys();
    }

    private void cleanRedisKeys() {
        try {
            Set<String> keys = redisTemplate.keys("orynx:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("Single workflow admission and release lifecycle in Redis")
    void testAdmissionAndReleaseLifecycle() {
        AdmissionDecision decision = controller.tryAdmit(1001L, 10L);

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.reason()).isEqualTo("ADMITTED");
        assertThat(decision.currentGlobal()).isEqualTo(1L);
        assertThat(decision.currentUser()).isEqualTo(1L);

        assertThat(controller.isAdmitted(1001L)).isTrue();
        assertThat(controller.getActiveGlobalCount()).isEqualTo(1L);
        assertThat(controller.getActiveUserCount(10L)).isEqualTo(1L);

        // Verify lease key and TTL
        String leaseKey = RedisWorkflowAdmissionController.LEASE_PREFIX + 1001;
        assertThat(redisTemplate.hasKey(leaseKey)).isTrue();
        Long ttl = redisTemplate.getExpire(leaseKey, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(3600L);

        // Release
        controller.release(1001L, 10L);

        assertThat(controller.isAdmitted(1001L)).isFalse();
        assertThat(controller.getActiveGlobalCount()).isEqualTo(0L);
        assertThat(controller.getActiveUserCount(10L)).isEqualTo(0L);
        assertThat(redisTemplate.hasKey(leaseKey)).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("Per-user quota enforcement prevents single user from monopolizing capacity")
    void testPerUserQuotaEnforcement() {
        properties.setMaxUserConcurrentWorkflows(2);

        AdmissionDecision d1 = controller.tryAdmit(2001L, 1L);
        AdmissionDecision d2 = controller.tryAdmit(2002L, 1L);

        assertThat(d1.admitted()).isTrue();
        assertThat(d2.admitted()).isTrue();

        // Third workflow for user 1 should be rejected
        assertThatThrownBy(() -> controller.tryAdmit(2003L, 1L))
                .isInstanceOf(WorkflowAdmissionException.class)
                .satisfies(ex -> {
                    WorkflowAdmissionException wae = (WorkflowAdmissionException) ex;
                    assertThat(wae.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(wae.getReason()).isEqualTo("USER_LIMIT_EXCEEDED");
                });

        // User 2 should NOT be blocked by user 1's quota exhaustion
        AdmissionDecision dUser2 = controller.tryAdmit(2004L, 2L);
        assertThat(dUser2.admitted()).isTrue();
        assertThat(dUser2.currentUser()).isEqualTo(1L);
    }

    @Test
    @Order(3)
    @DisplayName("Global capacity limit rejects all users when cluster limit is reached")
    void testGlobalCapacityLimitEnforcement() {
        properties.setMaxGlobalConcurrentWorkflows(3);
        properties.setMaxUserConcurrentWorkflows(10);

        controller.tryAdmit(3001L, 1L);
        controller.tryAdmit(3002L, 2L);
        controller.tryAdmit(3003L, 3L);

        assertThat(controller.getActiveGlobalCount()).isEqualTo(3L);

        // Any new user should now be rejected under global backpressure
        assertThatThrownBy(() -> controller.tryAdmit(3004L, 4L))
                .isInstanceOf(WorkflowAdmissionException.class)
                .satisfies(ex -> {
                    WorkflowAdmissionException wae = (WorkflowAdmissionException) ex;
                    assertThat(wae.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(wae.getReason()).isEqualTo("GLOBAL_LIMIT_EXCEEDED");
                });

        // Release one slot
        controller.release(3001L, 1L);
        assertThat(controller.getActiveGlobalCount()).isEqualTo(2L);

        // Now user 4 can be admitted
        AdmissionDecision dAfterRelease = controller.tryAdmit(3004L, 4L);
        assertThat(dAfterRelease.admitted()).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("Adversarial race test: 20 concurrent threads admitting under maxGlobal=5 guarantees exactly 5 admitted")
    void testConcurrentAdmissionRaceUnderCapacityLimit() throws Exception {
        int maxGlobal = 5;
        properties.setMaxGlobalConcurrentWorkflows(maxGlobal);
        properties.setMaxUserConcurrentWorkflows(20);

        int totalThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            final long wfId = 5000L + i;
            final long userId = 100L + (i % 5);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    controller.tryAdmit(wfId, userId);
                    successCount.incrementAndGet();
                } catch (WorkflowAdmissionException wae) {
                    if ("GLOBAL_LIMIT_EXCEEDED".equals(wae.getReason())) {
                        rejectedCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(wae);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(maxGlobal);
        assertThat(rejectedCount.get()).isEqualTo(totalThreads - maxGlobal);
        assertThat(controller.getActiveGlobalCount()).isEqualTo((long) maxGlobal);
    }

    @Test
    @Order(5)
    @DisplayName("Idempotent admission does not duplicate count or overwrite lease")
    void testAdmissionIdempotency() {
        AdmissionDecision first = controller.tryAdmit(6001L, 1L);
        assertThat(first.admitted()).isTrue();
        assertThat(first.reason()).isEqualTo("ADMITTED");

        AdmissionDecision second = controller.tryAdmit(6001L, 1L);
        assertThat(second.admitted()).isTrue();
        assertThat(second.reason()).isEqualTo("ALREADY_ADMITTED");

        assertThat(controller.getActiveGlobalCount()).isEqualTo(1L);
        assertThat(controller.getActiveUserCount(1L)).isEqualTo(1L);
    }

    @Test
    @Order(6)
    @DisplayName("Multiple duplicate releases are idempotent and safe")
    void testReleaseIdempotency() {
        controller.tryAdmit(7001L, 1L);
        assertThat(controller.getActiveGlobalCount()).isEqualTo(1L);

        controller.release(7001L, 1L);
        assertThat(controller.getActiveGlobalCount()).isEqualTo(0L);

        // Duplicate release
        controller.release(7001L, 1L);
        assertThat(controller.getActiveGlobalCount()).isEqualTo(0L);
    }

    @Test
    @Order(7)
    @DisplayName("Release with null userId cleans user quota set using stored lease metadata (M-03)")
    void testReleaseWithNullUserIdCleansUserQuotaFromLeaseMetadata() {
        long workflowId = 8001L;
        long realUserId = 42L;

        controller.tryAdmit(workflowId, realUserId);
        assertThat(controller.getActiveGlobalCount()).isEqualTo(1L);
        assertThat(controller.getActiveUserCount(realUserId)).isEqualTo(1L);

        // Caller releases with null userId (as done when workflow missing from DB)
        controller.release(workflowId, null);

        // User quota must NOT leak
        assertThat(controller.getActiveGlobalCount()).isEqualTo(0L);
        assertThat(controller.getActiveUserCount(realUserId)).isEqualTo(0L);
        assertThat(redisTemplate.hasKey(RedisWorkflowAdmissionController.LEASE_PREFIX + workflowId)).isFalse();
    }

    @Test
    @Order(8)
    @DisplayName("Release with mismatched userId cleans real user quota set using stored lease metadata (M-03)")
    void testReleaseWithMismatchedUserIdCleansRealUserQuotaFromLeaseMetadata() {
        long workflowId = 8002L;
        long realUserId = 42L;
        long wrongUserId = 999L;

        controller.tryAdmit(workflowId, realUserId);
        assertThat(controller.getActiveGlobalCount()).isEqualTo(1L);
        assertThat(controller.getActiveUserCount(realUserId)).isEqualTo(1L);

        // Caller releases with mismatched userId
        controller.release(workflowId, wrongUserId);

        // Real user's quota must NOT leak
        assertThat(controller.getActiveGlobalCount()).isEqualTo(0L);
        assertThat(controller.getActiveUserCount(realUserId)).isEqualTo(0L);
        assertThat(redisTemplate.hasKey(RedisWorkflowAdmissionController.LEASE_PREFIX + workflowId)).isFalse();
    }

    @Test
    @Order(9)
    @DisplayName("Cluster hash slot tagging: all admission keys share {admission} hash tag (m-01)")
    void testClusterHashSlotTagging() {
        assertThat(RedisWorkflowAdmissionController.GLOBAL_ACTIVE_KEY).contains("{admission}");
        assertThat(RedisWorkflowAdmissionController.USER_WORKFLOWS_PREFIX).contains("{admission}");
        assertThat(RedisWorkflowAdmissionController.LEASE_PREFIX).contains("{admission}");
        assertThat(RedisWorkflowAdmissionController.INITIALIZED_KEY).contains("{admission}");
    }
}
