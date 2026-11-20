package com.orynx.orchestrator.common.config;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkflowOwnershipMigrationPostgreSqlTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
    }

    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;
    private static WorkflowOwnershipMigration migration;

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
        migration = new WorkflowOwnershipMigration(jdbcTemplate, transactionManager);
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS workflows CASCADE");
    }

    @AfterAll
    static void tearDownAll() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS workflows CASCADE");
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Graceful skip when workflows table does not exist")
    void shouldGracefullySkipWhenTableDoesNotExist() {
        migration.run(null);

        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'workflows'",
                Integer.class
        );
        assertThat(tableCount).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Adds user_id column and index, preserves legacy workflows safely")
    void shouldAddColumnAndPreserveLegacyWorkflows() {
        // Create table without user_id (simulating legacy database schema)
        jdbcTemplate.execute("""
                CREATE TABLE workflows (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    goal TEXT NOT NULL,
                    status VARCHAR(50) NOT NULL
                )
                """);

        // Insert legacy workflows with unknown ownership
        jdbcTemplate.execute("INSERT INTO workflows (name, goal, status) VALUES ('Legacy Workflow 1', 'Goal 1', 'CREATED')");
        jdbcTemplate.execute("INSERT INTO workflows (name, goal, status) VALUES ('Legacy Workflow 2', 'Goal 2', 'RUNNING')");

        // Run ownership migration
        migration.run(null);

        // Verify column exists
        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'workflows' AND column_name = 'user_id'",
                Integer.class
        );
        assertThat(columnCount).isEqualTo(1);

        // Verify index exists
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'workflows' AND indexname = 'idx_workflows_user_id'",
                Integer.class
        );
        assertThat(indexCount).isEqualTo(1);

        // Verify legacy records preserved with user_id IS NULL
        List<Long> userIds = jdbcTemplate.query(
                "SELECT user_id FROM workflows ORDER BY id",
                (rs, rowNum) -> rs.getObject("user_id", Long.class)
        );
        assertThat(userIds).containsExactly(null, null);
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Idempotent - re-running migration on already migrated schema is a no-op")
    void shouldBeIdempotentOnSubsequentRuns() {
        jdbcTemplate.execute("""
                CREATE TABLE workflows (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    goal TEXT NOT NULL,
                    status VARCHAR(50) NOT NULL,
                    user_id BIGINT
                )
                """);

        // Run migration twice
        migration.run(null);
        migration.run(null);

        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'workflows' AND column_name = 'user_id'",
                Integer.class
        );
        assertThat(columnCount).isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Workflows can be inserted and queried by user_id")
    void shouldSupportInsertAndQueryByUserId() {
        jdbcTemplate.execute("""
                CREATE TABLE workflows (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    goal TEXT NOT NULL,
                    status VARCHAR(50) NOT NULL
                )
                """);

        migration.run(null);

        jdbcTemplate.execute("INSERT INTO workflows (name, goal, status, user_id) VALUES ('Alice WF', 'Goal', 'RUNNING', 101)");
        jdbcTemplate.execute("INSERT INTO workflows (name, goal, status, user_id) VALUES ('Bob WF', 'Goal', 'RUNNING', 202)");

        Integer aliceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflows WHERE user_id = 101",
                Integer.class
        );
        Integer bobCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflows WHERE user_id = 202",
                Integer.class
        );

        assertThat(aliceCount).isEqualTo(1);
        assertThat(bobCount).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Concurrent execution across multiple threads with advisory locking succeeds deterministically")
    void shouldHandleConcurrentExecutionWithAdvisoryLocking() throws Exception {
        jdbcTemplate.execute("""
                CREATE TABLE workflows (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    goal TEXT NOT NULL,
                    status VARCHAR(50) NOT NULL
                )
                """);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                migration.run(null);
                return true;
            }));
        }

        for (Future<Boolean> future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
        }
        executor.shutdown();

        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'workflows' AND column_name = 'user_id'",
                Integer.class
        );
        assertThat(columnCount).isEqualTo(1);
    }
}
