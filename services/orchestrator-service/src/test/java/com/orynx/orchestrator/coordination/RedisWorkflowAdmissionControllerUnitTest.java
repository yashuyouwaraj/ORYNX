package com.orynx.orchestrator.coordination;

import com.orynx.orchestrator.coordination.admission.AdmissionDecision;
import com.orynx.orchestrator.coordination.admission.AdmissionFailurePolicy;
import com.orynx.orchestrator.coordination.admission.RedisWorkflowAdmissionController;
import com.orynx.orchestrator.coordination.admission.WorkflowAdmissionException;
import com.orynx.orchestrator.coordination.config.RedisCoordinationProperties;
import com.orynx.orchestrator.coordination.metrics.RedisCoordinationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RedisWorkflowAdmissionControllerUnitTest {

    private StringRedisTemplate redisTemplate;
    private RedisScript<List> admitScript;
    private RedisScript<List> releaseScript;
    private RedisCoordinationProperties properties;
    private RedisCoordinationMetrics metrics;
    private RedisWorkflowAdmissionController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        admitScript = mock(RedisScript.class);
        releaseScript = mock(RedisScript.class);
        properties = new RedisCoordinationProperties();
        properties.setEnabled(true);
        properties.setMaxGlobalConcurrentWorkflows(10);
        properties.setMaxUserConcurrentWorkflows(3);
        properties.setWorkflowLeaseTtlSeconds(3600);
        properties.setRetryAfterSeconds(5);
        properties.setFailurePolicy(AdmissionFailurePolicy.FAIL_CLOSED);

        metrics = new RedisCoordinationMetrics(new SimpleMeterRegistry());
        controller = new RedisWorkflowAdmissionController(
                redisTemplate,
                admitScript,
                releaseScript,
                properties,
                metrics
        );
    }

    @Test
    @DisplayName("tryAdmit accepts workflow when within global and user limits")
    void tryAdmitSuccessWithinLimits() {
        when(redisTemplate.execute(eq(admitScript), anyList(), any(Object[].class)))
                .thenReturn(List.of(1, "ADMITTED", "1", "1"));

        AdmissionDecision decision = controller.tryAdmit(101L, 1L);

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.reason()).isEqualTo("ADMITTED");
        assertThat(decision.currentGlobal()).isEqualTo(1L);
        assertThat(decision.currentUser()).isEqualTo(1L);
        assertThat(decision.leaseToken()).isNotBlank();
        assertThat(decision.bypassedDueToFailure()).isFalse();
    }

    @Test
    @DisplayName("tryAdmit handles idempotent admission when workflow already active")
    void tryAdmitAlreadyAdmittedIdempotent() {
        when(redisTemplate.execute(eq(admitScript), anyList(), any(Object[].class)))
                .thenReturn(List.of(1, "ALREADY_ADMITTED", "3", "1"));

        AdmissionDecision decision = controller.tryAdmit(101L, 1L);

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.reason()).isEqualTo("ALREADY_ADMITTED");
        assertThat(decision.currentGlobal()).isEqualTo(3L);
    }

    @Test
    @DisplayName("tryAdmit throws 429 when global limit exceeded")
    void tryAdmitRejectsOnGlobalLimitExceeded() {
        when(redisTemplate.execute(eq(admitScript), anyList(), any(Object[].class)))
                .thenReturn(List.of(0, "GLOBAL_LIMIT_EXCEEDED", "10", "1"));

        assertThatThrownBy(() -> controller.tryAdmit(102L, 1L))
                .isInstanceOf(WorkflowAdmissionException.class)
                .satisfies(ex -> {
                    WorkflowAdmissionException wae = (WorkflowAdmissionException) ex;
                    assertThat(wae.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(wae.getReason()).isEqualTo("GLOBAL_LIMIT_EXCEEDED");
                    assertThat(wae.getRetryAfterSeconds()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("tryAdmit throws 429 when per-user quota exceeded")
    void tryAdmitRejectsOnUserLimitExceeded() {
        when(redisTemplate.execute(eq(admitScript), anyList(), any(Object[].class)))
                .thenReturn(List.of(0, "USER_LIMIT_EXCEEDED", "5", "3"));

        assertThatThrownBy(() -> controller.tryAdmit(103L, 2L))
                .isInstanceOf(WorkflowAdmissionException.class)
                .satisfies(ex -> {
                    WorkflowAdmissionException wae = (WorkflowAdmissionException) ex;
                    assertThat(wae.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(wae.getReason()).isEqualTo("USER_LIMIT_EXCEEDED");
                    assertThat(wae.getMessage()).contains("user 2");
                });
    }

    @Test
    @DisplayName("tryAdmit with FAIL_CLOSED policy throws 503 when Redis is unavailable")
    void tryAdmitFailsClosedWhenRedisDown() {
        when(redisTemplate.execute(eq(admitScript), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        properties.setFailurePolicy(AdmissionFailurePolicy.FAIL_CLOSED);

        assertThatThrownBy(() -> controller.tryAdmit(104L, 1L))
                .isInstanceOf(WorkflowAdmissionException.class)
                .satisfies(ex -> {
                    WorkflowAdmissionException wae = (WorkflowAdmissionException) ex;
                    assertThat(wae.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(wae.getReason()).isEqualTo("REDIS_UNAVAILABLE");
                });
    }

    @Test
    @DisplayName("tryAdmit with FAIL_OPEN policy allows execution and marks bypassed when Redis is unavailable")
    void tryAdmitFailsOpenWhenConfigured() {
        when(redisTemplate.execute(eq(admitScript), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Connection timeout"));

        properties.setFailurePolicy(AdmissionFailurePolicy.FAIL_OPEN);

        AdmissionDecision decision = controller.tryAdmit(105L, 1L);

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.bypassedDueToFailure()).isTrue();
        assertThat(decision.reason()).contains("FAIL_OPEN_REDIS_ERROR");
    }

    @Test
    @DisplayName("tryAdmit bypasses admission check entirely when admission is disabled")
    void tryAdmitBypassedWhenDisabled() {
        properties.setEnabled(false);

        AdmissionDecision decision = controller.tryAdmit(106L, 1L);

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.reason()).isEqualTo("ADMISSION_DISABLED");
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("release safely handles Redis exceptions without failing caller")
    void releaseSwallowsRedisExceptionSafely() {
        when(redisTemplate.execute(eq(releaseScript), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Redis offline"));

        // Should not throw
        controller.release(107L, 1L);
    }
}
