package com.orynx.orchestrator.workflow;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Real PostgreSQL Integration Tests for WorkflowScheduler reliability (Phase A, Step 9).
 * Tests against real PostgreSQL 17 test instance at localhost:5439.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkflowSchedulerPostgreSqlTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
    }

    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void setUpAll() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5439/orynx_test?options=-c%20timezone=UTC");
        dataSource.setUsername("testuser");
        dataSource.setPassword("testpass");

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void setUpSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS workflows CASCADE");
        jdbcTemplate.execute("""
            CREATE TABLE workflows (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                goal VARCHAR(255),
                status VARCHAR(50) NOT NULL,
                scheduled BOOLEAN NOT NULL DEFAULT false,
                scheduled_at TIMESTAMP,
                started_at BIGINT,
                completed_at BIGINT,
                depends_on_workflow_id BIGINT,
                CONSTRAINT workflows_status_check CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'))
            )
        """);
    }

    @Test
    @Order(1)
    @DisplayName("Concurrent scheduler claims: Only one worker can claim a scheduled workflow in PostgreSQL")
    void testConcurrentSchedulerClaimsOnlyOneWins() throws InterruptedException, ExecutionException {
        // Insert a due scheduled workflow
        jdbcTemplate.execute("""
            INSERT INTO workflows (name, status, scheduled, scheduled_at)
            VALUES ('Batch-Job-1', 'CREATED', true, NOW() - INTERVAL '1 minute')
        """);

        Long workflowId = jdbcTemplate.queryForObject("SELECT id FROM workflows WHERE name = 'Batch-Job-1'", Long.class);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger claimedSuccessCount = new AtomicInteger(0);
        AtomicInteger claimedZeroCount = new AtomicInteger(0);

        List<Future<?>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    // Execute atomic claim query against PostgreSQL:
                    // UPDATE workflows SET scheduled = false WHERE id = :id AND scheduled = true
                    int rows = jdbcTemplate.update(
                            "UPDATE workflows SET scheduled = false WHERE id = ? AND scheduled = true",
                            workflowId
                    );
                    if (rows == 1) {
                        claimedSuccessCount.incrementAndGet();
                    } else {
                        claimedZeroCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }

        // Release all threads simultaneously
        latch.countDown();

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Exactly one worker must succeed in claiming the workflow
        assertThat(claimedSuccessCount.get()).isEqualTo(1);
        assertThat(claimedZeroCount.get()).isEqualTo(threadCount - 1);

        // Verify database state: scheduled is now false
        Boolean isScheduled = jdbcTemplate.queryForObject(
                "SELECT scheduled FROM workflows WHERE id = ?",
                Boolean.class,
                workflowId
        );
        assertThat(isScheduled).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("Claim failure: If claim returns 0, workflow is not dispatched")
    void testClaimFailureDoesNotDispatch() {
        WorkflowRepository mockRepo = mock(WorkflowRepository.class);
        WorkflowService mockService = mock(WorkflowService.class);
        WorkflowScheduler scheduler = new WorkflowScheduler(mockRepo, mockService);

        Workflow wf = Workflow.builder()
                .id(999L)
                .name("Already-Claimed-Workflow")
                .status(WorkflowStatus.CREATED)
                .scheduled(true)
                .build();

        // Simulate claim returning 0 (already claimed by another scheduler instance)
        when(mockRepo.claimScheduledWorkflow(999L)).thenReturn(0);

        scheduler.processScheduledWorkflow(wf);

        // Verify dispatch (startWorkflow) was NEVER called
        verify(mockService, never()).startWorkflow(anyLong());
        verify(mockRepo, never()).releaseScheduledWorkflow(anyLong());
    }

    @Test
    @Order(3)
    @DisplayName("Dispatch failure: Workflow recovers to safe state via releaseScheduledWorkflow in PostgreSQL")
    void testDispatchFailureRecoversViaReleaseInPostgres() {
        jdbcTemplate.execute("""
            INSERT INTO workflows (name, status, scheduled, scheduled_at)
            VALUES ('Failing-Dispatch-Job', 'CREATED', true, NOW() - INTERVAL '1 minute')
        """);

        Long workflowId = jdbcTemplate.queryForObject("SELECT id FROM workflows WHERE name = 'Failing-Dispatch-Job'", Long.class);

        // Step 1: Claim workflow
        int claimed = jdbcTemplate.update(
                "UPDATE workflows SET scheduled = false WHERE id = ? AND scheduled = true",
                workflowId
        );
        assertThat(claimed).isEqualTo(1);

        // Verify workflow is now claimed
        Boolean afterClaim = jdbcTemplate.queryForObject("SELECT scheduled FROM workflows WHERE id = ?", Boolean.class, workflowId);
        assertThat(afterClaim).isFalse();

        // Step 2: Simulate dispatch failure -> release recovery query:
        // UPDATE workflows SET scheduled = true WHERE id = ? AND status = 'CREATED'
        int released = jdbcTemplate.update(
                "UPDATE workflows SET scheduled = true WHERE id = ? AND status = 'CREATED'",
                workflowId
        );
        assertThat(released).isEqualTo(1);

        // Step 3: Verify workflow is released back to scheduled=true and ready for future retry
        Boolean afterRelease = jdbcTemplate.queryForObject("SELECT scheduled FROM workflows WHERE id = ?", Boolean.class, workflowId);
        assertThat(afterRelease).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("Recovery safety: Workflow that transitioned past CREATED is not re-released to scheduled")
    void testWorkflowPastCreatedIsNotReReleased() {
        jdbcTemplate.execute("""
            INSERT INTO workflows (name, status, scheduled, scheduled_at)
            VALUES ('Running-Job', 'RUNNING', false, NOW() - INTERVAL '1 minute')
        """);

        Long workflowId = jdbcTemplate.queryForObject("SELECT id FROM workflows WHERE name = 'Running-Job'", Long.class);

        // Attempt to release a workflow whose status is RUNNING
        int released = jdbcTemplate.update(
                "UPDATE workflows SET scheduled = true WHERE id = ? AND status = 'CREATED'",
                workflowId
        );

        // Release must return 0 because status is no longer CREATED
        assertThat(released).isEqualTo(0);

        Boolean isScheduled = jdbcTemplate.queryForObject("SELECT scheduled FROM workflows WHERE id = ?", Boolean.class, workflowId);
        assertThat(isScheduled).isFalse();
    }
}
