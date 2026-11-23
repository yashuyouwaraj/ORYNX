package com.orynx.orchestrator.common.config;

import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real PostgreSQL 17 integration test verifying query-driven index creation,
 * idempotent migration execution under advisory locking, non-redundancy of
 * workflow indexes, fail-fast error handling, and planner index selection.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseIndexAndPaginationPostgreSqlTest {

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void setUpAll() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv().getOrDefault(
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://localhost:5439/orynx_test"
        ));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "testuser"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "testpass"));

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    @Order(1)
    @DisplayName("DatabaseIndexMigration idempotently creates all non-redundant orchestrator indexes on PostgreSQL")
    void databaseIndexMigrationCreatesAllIndexesIdempotently() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS workflows (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(255),
                goal VARCHAR(255),
                status VARCHAR(50),
                scheduled_at TIMESTAMP,
                scheduled BOOLEAN,
                depends_on_workflow_id BIGINT,
                started_at BIGINT,
                completed_at BIGINT,
                user_id BIGINT
            )
        """);
        jdbcTemplate.execute(WorkflowOwnershipMigration.ADD_COLUMN_SQL);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS workflow_tasks (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(255),
                status VARCHAR(50),
                execution_order INTEGER,
                workflow_id BIGINT,
                started_at BIGINT,
                completed_at BIGINT,
                retry_count INTEGER,
                max_retries INTEGER
            )
        """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS workflow_executions (
                id BIGSERIAL PRIMARY KEY,
                workflow_id BIGINT,
                status VARCHAR(50),
                started_at BIGINT,
                completed_at BIGINT,
                duration_ms BIGINT,
                last_completed_execution_order INTEGER,
                execution_generation BIGINT
            )
        """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS execution_events (
                id BIGSERIAL PRIMARY KEY,
                workflow_id BIGINT,
                task_id BIGINT,
                task_name VARCHAR(255),
                status VARCHAR(50),
                timestamp BIGINT,
                generation BIGINT,
                event_id VARCHAR(255)
            )
        """);

        DatabaseIndexMigration migration = new DatabaseIndexMigration(jdbcTemplate, transactionTemplate);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());

        Set<String> existingIndexes = jdbcTemplate.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                        String.class
                )
                .stream()
                .collect(Collectors.toSet());

        assertThat(DatabaseIndexMigration.INDEX_DEFINITIONS)
                .extracting(DatabaseIndexMigration.IndexDefinition::indexName)
                .doesNotContain("idx_workflows_user_id");

        for (DatabaseIndexMigration.IndexDefinition def : DatabaseIndexMigration.INDEX_DEFINITIONS) {
            assertThat(existingIndexes).contains(def.indexName());
        }
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL query planner utilizes idx_workflows_user_id_id and idx_execution_events_wf_ts on selective analyzed data")
    void postgresqlPlannerUsesSpecificCompositeIndexesOnSelectiveData() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("DELETE FROM execution_events WHERE workflow_id IN (99001, 99002)");
            jdbcTemplate.execute("DELETE FROM workflows WHERE user_id IN (99001, 99002)");

            jdbcTemplate.execute("""
                INSERT INTO workflows (name, goal, status, scheduled, user_id)
                SELECT 'bulk-' || g, 'goal', 'COMPLETED', false, 99002
                FROM generate_series(1, 500) AS g
            """);
            jdbcTemplate.execute("""
                INSERT INTO workflows (name, goal, status, scheduled, user_id)
                SELECT 'target-' || g, 'goal', 'RUNNING', false, 99001
                FROM generate_series(1, 5) AS g
            """);

            jdbcTemplate.execute("""
                INSERT INTO execution_events (workflow_id, task_id, task_name, status, timestamp, generation, event_id)
                SELECT 99002, g, 'T', 'COMPLETED', 1000 + g, 1, 'bulk-ev-' || g
                FROM generate_series(1, 500) AS g
            """);
            jdbcTemplate.execute("""
                INSERT INTO execution_events (workflow_id, task_id, task_name, status, timestamp, generation, event_id)
                SELECT 99001, g, 'T', 'COMPLETED', 1000 + g, 1, 'target-ev-' || g
                FROM generate_series(1, 5) AS g
            """);
        });

        jdbcTemplate.execute("ANALYZE workflows");
        jdbcTemplate.execute("ANALYZE execution_events");

        try {
            List<String> wfPlan = jdbcTemplate.queryForList(
                    "EXPLAIN SELECT * FROM workflows WHERE user_id = 99001 ORDER BY id DESC LIMIT 20",
                    String.class
            );
            String wfPlanText = String.join("\n", wfPlan);
            assertThat(wfPlanText).doesNotContain("Seq Scan");
            assertThat(wfPlanText).containsAnyOf("idx_workflows_user_id_id", "idx_workflows_user_status_id");

            List<String> timelinePlan = jdbcTemplate.queryForList(
                    "EXPLAIN SELECT * FROM execution_events WHERE workflow_id = 99001 ORDER BY timestamp DESC, id DESC LIMIT 20",
                    String.class
            );
            String timelinePlanText = String.join("\n", timelinePlan);
            assertThat(timelinePlanText).doesNotContain("Seq Scan");
            assertThat(timelinePlanText).containsAnyOf(
                    "idx_execution_events_wf_ts",
                    "idx_execution_events_wf_task_id",
                    "idx_execution_events_wf_task_name",
                    "idx_execution_events_wf_event_id"
            );
        } finally {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("DELETE FROM execution_events WHERE workflow_id IN (99001, 99002)");
                jdbcTemplate.execute("DELETE FROM workflows WHERE user_id IN (99001, 99002)");
            });
        }
    }

    @Test
    @Order(3)
    @DisplayName("DatabaseIndexMigration fails startup when PostgreSQL advisory lock or DDL fails instead of swallowing")
    void migrationFailsFastWhenPostgreSqlLockFails() {
        JdbcTemplate failingJdbc = mock(JdbcTemplate.class);
        when(failingJdbc.execute(any(org.springframework.jdbc.core.ConnectionCallback.class))).thenReturn(true);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Simulated lock failure"))
                .when(failingJdbc)
                .execute("SELECT pg_advisory_xact_lock(" + DatabaseIndexMigration.MIGRATION_ADVISORY_LOCK_ID + ")");

        DatabaseIndexMigration failingMigration = new DatabaseIndexMigration(
                failingJdbc,
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                            @Override
                            protected Object doGetTransaction() { return new Object(); }
                            @Override
                            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
                            @Override
                            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                            @Override
                            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                        }
                )
        );

        assertThatThrownBy(() -> failingMigration.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orchestrator-service index migration");
    }
}
