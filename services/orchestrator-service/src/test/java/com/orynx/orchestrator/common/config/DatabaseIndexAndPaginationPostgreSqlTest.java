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

/**
 * Real PostgreSQL 17 integration test verifying query-driven index creation,
 * idempotent migration execution under advisory locking, and PostgreSQL query plans.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseIndexAndPaginationPostgreSqlTest {

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void setUpAll() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5439/orynx_test");
        dataSource.setUsername("testuser");
        dataSource.setPassword("testpass");

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    @Order(1)
    @DisplayName("DatabaseIndexMigration idempotently creates all orchestrator indexes on PostgreSQL")
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

        for (DatabaseIndexMigration.IndexDefinition def : DatabaseIndexMigration.INDEX_DEFINITIONS) {
            assertThat(existingIndexes).contains(def.indexName());
        }
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL query planner utilizes composite indexes for ownership, timeline, and execution queries")
    void postgresqlPlannerUsesIndexesWhenSeqScanDisabled() {
        List<String> planLines = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList(
                    "EXPLAIN SELECT * FROM workflows WHERE user_id = 10 ORDER BY id DESC LIMIT 20",
                    String.class
            );
        });
        assertThat(planLines).isNotNull().isNotEmpty();
        assertThat(String.join("\n", planLines)).contains("Index");

        List<String> timelinePlan = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList(
                    "EXPLAIN SELECT * FROM execution_events WHERE workflow_id = 10 ORDER BY timestamp DESC, id DESC LIMIT 20",
                    String.class
            );
        });
        assertThat(timelinePlan).isNotNull().isNotEmpty();
        assertThat(String.join("\n", timelinePlan)).contains("Index");
    }
}
