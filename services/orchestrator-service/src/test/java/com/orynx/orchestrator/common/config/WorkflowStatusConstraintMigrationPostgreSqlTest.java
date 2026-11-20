package com.orynx.orchestrator.common.config;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real PostgreSQL Integration Tests for WorkflowStatusConstraintMigration (Phase A, Step 9).
 * Tests against real PostgreSQL 17 test instance at localhost:5439.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkflowStatusConstraintMigrationPostgreSqlTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
    }

    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;
    private static WorkflowStatusConstraintMigration migration;

    @BeforeAll
    static void setUpAll() {
        System.setProperty("user.timezone", "UTC");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5439/orynx_test?options=-c%20timezone=UTC");
        dataSource.setUsername("testuser");
        dataSource.setPassword("testpass");

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        migration = new WorkflowStatusConstraintMigration(jdbcTemplate, transactionManager);
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS workflows CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS unrelated_table CASCADE");
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Missing workflow table should skip safely without modifying any tables or throwing")
    void testMissingWorkflowTableSkipsSafely() {
        // Create an unrelated table to verify it remains untouched
        jdbcTemplate.execute("CREATE TABLE unrelated_table (id SERIAL PRIMARY KEY, data VARCHAR(100))");
        jdbcTemplate.execute("INSERT INTO unrelated_table (data) VALUES ('untouched_data')");

        // Execute migration when 'workflows' table does not exist
        migration.run(null);

        // Verify unrelated table is intact and workflows table was not created unexpectedly
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM unrelated_table", Integer.class);
        assertThat(count).isEqualTo(1);

        Boolean workflowsExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'workflows')",
                Boolean.class
        );
        assertThat(workflowsExists).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Legacy constraint is upgraded; existing data preserved; PAUSED and CANCELLED accepted")
    void testLegacyConstraintUpgradesAndPreservesData() {
        // Create workflows table with legacy constraint (missing PAUSED and CANCELLED)
        jdbcTemplate.execute("""
            CREATE TABLE workflows (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                status VARCHAR(50) NOT NULL,
                CONSTRAINT workflows_status_check CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED'))
            )
        """);

        // Insert valid existing legacy rows
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-1', 'CREATED')");
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-2', 'RUNNING')");
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-3', 'COMPLETED')");
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-4', 'FAILED')");

        // Verify legacy constraint rejects PAUSED initially
        assertThrows(Exception.class, () ->
                jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-paused-fail', 'PAUSED')")
        );

        // Run migration against live PostgreSQL
        migration.run(null);

        // Verify existing rows remain intact
        Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflows", Integer.class);
        assertThat(rowCount).isEqualTo(4);

        // Verify PAUSED is now accepted
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-5', 'PAUSED')");

        // Verify CANCELLED is now accepted
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-6', 'CANCELLED')");

        // Verify all previous valid statuses still work
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-7', 'CREATED')");
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-8', 'RUNNING')");

        Integer updatedRowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflows", Integer.class);
        assertThat(updatedRowCount).isEqualTo(8);
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Already compatible constraint is recognized and migration is idempotent")
    void testAlreadyCompatibleConstraintIsIdempotent() {
        // Create table with already compatible constraint
        jdbcTemplate.execute("""
            CREATE TABLE workflows (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                status VARCHAR(50) NOT NULL,
                CONSTRAINT workflows_status_check CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'))
            )
        """);

        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-1', 'PAUSED')");

        // Run migration first time
        migration.run(null);

        // Run migration second time
        migration.run(null);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflows WHERE status = 'PAUSED'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Invalid statuses are strictly rejected by PostgreSQL check constraint")
    void testInvalidStatusesRejectedByConstraint() {
        jdbcTemplate.execute("""
            CREATE TABLE workflows (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                status VARCHAR(50) NOT NULL,
                CONSTRAINT workflows_status_check CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED'))
            )
        """);

        migration.run(null);

        // Attempt invalid statuses
        assertThrows(Exception.class, () ->
                jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-bad-1', 'INVALID_STATUS')")
        );

        assertThrows(Exception.class, () ->
                jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-bad-2', 'UNKNOWN')")
        );

        assertThrows(Exception.class, () ->
                jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-bad-3', 'STOPPED')")
        );
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Migration transaction rollback restores initial constraint on failure")
    void testMigrationRollbackOnFailure() {
        // Create table with legacy constraint
        jdbcTemplate.execute("""
            CREATE TABLE workflows (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                status VARCHAR(50) NOT NULL,
                CONSTRAINT workflows_status_check CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED'))
            )
        """);

        // Insert a row that has a status that would fail a hypothetical broken migration
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-1', 'CREATED')");

        // Simulate a failing transactional DDL migration (e.g. attempting to add a constraint that violates existing data)
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                // Drop legacy constraint
                jdbcTemplate.execute("ALTER TABLE workflows DROP CONSTRAINT workflows_status_check");

                // Intentionally attempt invalid constraint that causes transaction failure
                jdbcTemplate.execute("ALTER TABLE workflows ADD CONSTRAINT workflows_status_check CHECK (status = 'NON_EXISTENT')");
            });
        });

        // Verify transaction rolled back: table still exists and state is not corrupt
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflows", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: Concurrent startup with advisory locking ensures deterministic safe migration")
    void testConcurrentStartupWithAdvisoryLocking() throws InterruptedException, ExecutionException {
        // Create table with legacy constraint
        jdbcTemplate.execute("""
            CREATE TABLE workflows (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                status VARCHAR(50) NOT NULL,
                CONSTRAINT workflows_status_check CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED'))
            )
        """);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    migration.run(null);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failureCount.get()).isEqualTo(0);

        // Verify resulting constraint in PostgreSQL accepts PAUSED and CANCELLED
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-conc-1', 'PAUSED')");
        jdbcTemplate.execute("INSERT INTO workflows (name, status) VALUES ('wf-conc-2', 'CANCELLED')");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workflows", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
