package com.orynx.orchestrator.workflow;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL Concurrency and Lifecycle Transition Integration Tests (Phase A, Step 14).
 * Validates row-level locking (SELECT FOR UPDATE), atomic transitions, idempotency,
 * and checkpoint integrity under concurrent operations on PostgreSQL 17 at localhost:5439.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkflowLifecyclePostgreSqlTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
    }

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void setUpAll() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5439/orynx_test?options=-c%20timezone=UTC");
        dataSource.setUsername("testuser");
        dataSource.setPassword("testpass");

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUpSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS workflow_executions CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS workflows CASCADE");

        jdbcTemplate.execute("""
            CREATE TABLE workflows (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                goal VARCHAR(255),
                status VARCHAR(50) NOT NULL,
                user_id BIGINT,
                scheduled BOOLEAN NOT NULL DEFAULT false,
                scheduled_at TIMESTAMP,
                started_at BIGINT,
                completed_at BIGINT,
                depends_on_workflow_id BIGINT,
                CONSTRAINT workflows_status_check CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'))
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE workflow_executions (
                id SERIAL PRIMARY KEY,
                workflow_id BIGINT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
                status VARCHAR(50) NOT NULL,
                started_at BIGINT NOT NULL,
                completed_at BIGINT,
                duration_ms BIGINT,
                last_completed_execution_order INT,
                error_message TEXT
            )
        """);
    }

    @Test
    @Order(1)
    @DisplayName("Concurrent startWorkflow: Pessimistic lock prevents duplicate execution; exactly 1 succeeds")
    void testConcurrentStartPessimisticLock() throws InterruptedException, ExecutionException {
        jdbcTemplate.execute("""
            INSERT INTO workflows (name, goal, status, user_id, scheduled)
            VALUES ('Concurrency-Pipeline', 'Test concurrent start', 'CREATED', 100, false)
        """);

        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflows WHERE name = 'Concurrency-Pipeline'", Long.class
        );

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        List<Future<?>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    // Simulate WorkflowService.startWorkflow with SELECT FOR UPDATE inside transaction
                    transactionTemplate.execute(status -> {
                        List<String> rows = jdbcTemplate.query(
                                "SELECT status FROM workflows WHERE id = ? FOR UPDATE",
                                (rs, rowNum) -> rs.getString("status"),
                                workflowId
                        );

                        if (rows.isEmpty()) {
                            throw new IllegalStateException("Workflow not found");
                        }

                        String currentStatus = rows.get(0);
                        if (!"CREATED".equals(currentStatus)) {
                            throw new IllegalStateException("Cannot start workflow in status " + currentStatus);
                        }

                        // Transition to RUNNING
                        jdbcTemplate.update(
                                "UPDATE workflows SET status = 'RUNNING', started_at = ? WHERE id = ?",
                                System.currentTimeMillis(), workflowId
                        );
                        return true;
                    });
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception e) {
                    // unexpected error
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(threadCount - 1);

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM workflows WHERE id = ?", String.class, workflowId
        );
        assertThat(finalStatus).isEqualTo("RUNNING");
    }

    @Test
    @Order(2)
    @DisplayName("Cancel PAUSED workflow in PostgreSQL: Checkpoint is preserved and status is CANCELLED")
    void testCancelPausedWorkflowPreservesCheckpointInDb() {
        jdbcTemplate.execute("""
            INSERT INTO workflows (name, goal, status, user_id, scheduled, started_at)
            VALUES ('Paused-Job', 'Testing checkpoint preservation', 'PAUSED', 100, false, 1000000)
        """);

        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflows WHERE name = 'Paused-Job'", Long.class
        );

        jdbcTemplate.update("""
            INSERT INTO workflow_executions (workflow_id, status, started_at, last_completed_execution_order)
            VALUES (?, 'PAUSED', 1000000, 4)
        """, workflowId);

        // Perform cancellation transaction preserving checkpoint
        transactionTemplate.execute(status -> {
            String currentStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM workflows WHERE id = ? FOR UPDATE", String.class, workflowId
            );

            assertThat(currentStatus).isEqualTo("PAUSED");

            long now = System.currentTimeMillis();
            jdbcTemplate.update(
                    "UPDATE workflows SET status = 'CANCELLED', completed_at = ? WHERE id = ?",
                    now, workflowId
            );

            jdbcTemplate.update("""
                UPDATE workflow_executions
                SET status = 'CANCELLED', completed_at = ?, duration_ms = ? - started_at
                WHERE workflow_id = ? AND status = 'PAUSED'
            """, now, now, workflowId);

            return null;
        });

        // Verify workflow table
        String wfStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM workflows WHERE id = ?", String.class, workflowId
        );
        assertThat(wfStatus).isEqualTo("CANCELLED");

        // Verify execution table: checkpoint MUST BE 4!
        Integer checkpoint = jdbcTemplate.queryForObject(
                "SELECT last_completed_execution_order FROM workflow_executions WHERE workflow_id = ?",
                Integer.class, workflowId
        );
        assertThat(checkpoint).isEqualTo(4);

        String execStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_executions WHERE workflow_id = ?",
                String.class, workflowId
        );
        assertThat(execStatus).isEqualTo("CANCELLED");
    }

    @Test
    @Order(3)
    @DisplayName("Scheduled workflow cancellation prevents scheduler claim in PostgreSQL")
    void testCancelledScheduledWorkflowCannotBeClaimed() {
        jdbcTemplate.execute("""
            INSERT INTO workflows (name, goal, status, user_id, scheduled, scheduled_at)
            VALUES ('Scheduled-Cancel-Job', 'Cancel scheduled', 'CREATED', 100, true, NOW() - INTERVAL '1 minute')
        """);

        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflows WHERE name = 'Scheduled-Cancel-Job'", Long.class
        );

        // Cancel the scheduled workflow
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                    "UPDATE workflows SET status = 'CANCELLED', scheduled = false, completed_at = ? WHERE id = ?",
                    System.currentTimeMillis(), workflowId
            );
            return null;
        });

        // Attempt atomic scheduler claim query (from WorkflowRepository.claimScheduledWorkflow)
        int updatedRows = jdbcTemplate.update("""
            UPDATE workflows
            SET status = 'RUNNING', started_at = ?
            WHERE id = ? AND scheduled = true AND status = 'CREATED'
        """, System.currentTimeMillis(), workflowId);

        assertThat(updatedRows).isEqualTo(0);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM workflows WHERE id = ?", String.class, workflowId
        );
        assertThat(status).isEqualTo("CANCELLED");
    }

    @Test
    @Order(4)
    @DisplayName("Concurrent cancellation: Idempotent cancellation without duplicate side-effects")
    void testConcurrentCancellationIdempotent() throws InterruptedException, ExecutionException {
        jdbcTemplate.execute("""
            INSERT INTO workflows (name, goal, status, user_id, scheduled, started_at)
            VALUES ('Concurrent-Cancel', 'Concurrent cancel test', 'RUNNING', 100, false, 1000000)
        """);

        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflows WHERE name = 'Concurrent-Cancel'", Long.class
        );

        jdbcTemplate.update("""
            INSERT INTO workflow_executions (workflow_id, status, started_at)
            VALUES (?, 'RUNNING', 1000000)
        """, workflowId);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger cancelledTransitions = new AtomicInteger(0);
        AtomicInteger alreadyCancelledNoOps = new AtomicInteger(0);

        List<Future<?>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    transactionTemplate.execute(status -> {
                        String current = jdbcTemplate.queryForObject(
                                "SELECT status FROM workflows WHERE id = ? FOR UPDATE", String.class, workflowId
                        );

                        if ("CANCELLED".equals(current)) {
                            alreadyCancelledNoOps.incrementAndGet();
                            return true;
                        }

                        jdbcTemplate.update(
                                "UPDATE workflows SET status = 'CANCELLED', completed_at = ? WHERE id = ?",
                                System.currentTimeMillis(), workflowId
                        );
                        cancelledTransitions.incrementAndGet();
                        return true;
                    });
                } catch (Exception e) {
                    // unexpected
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(cancelledTransitions.get()).isEqualTo(1);
        assertThat(alreadyCancelledNoOps.get()).isEqualTo(threadCount - 1);
    }
}
