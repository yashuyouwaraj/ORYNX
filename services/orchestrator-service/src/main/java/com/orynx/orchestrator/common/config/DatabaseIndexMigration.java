package com.orynx.orchestrator.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Idempotently provisions query-driven PostgreSQL indexes for orchestrator-service
 * tables on existing databases where Hibernate ddl-auto=update does not retrofit
 * newly declared @Index annotations onto existing tables.
 */
@Component
@Order(3)
@Slf4j
public class DatabaseIndexMigration implements ApplicationRunner {

    public static final long MIGRATION_ADVISORY_LOCK_ID = 849201851L;

    public record IndexDefinition(
            String tableName,
            List<String> requiredColumns,
            String indexName,
            String createSql
    ) {}

    public static final List<IndexDefinition> INDEX_DEFINITIONS = List.of(
            new IndexDefinition("workflows", List.of("user_id", "id"), "idx_workflows_user_id_id",
                    "CREATE INDEX IF NOT EXISTS idx_workflows_user_id_id ON workflows (user_id, id DESC)"),
            new IndexDefinition("workflows", List.of("user_id", "status", "id"), "idx_workflows_user_status_id",
                    "CREATE INDEX IF NOT EXISTS idx_workflows_user_status_id ON workflows (user_id, status, id DESC)"),
            new IndexDefinition("workflows", List.of("status", "id"), "idx_workflows_status_id",
                    "CREATE INDEX IF NOT EXISTS idx_workflows_status_id ON workflows (status, id DESC)"),
            new IndexDefinition("workflows", List.of("scheduled", "status", "scheduled_at"), "idx_workflows_scheduled_due",
                    "CREATE INDEX IF NOT EXISTS idx_workflows_scheduled_due ON workflows (scheduled, status, scheduled_at)"),
            new IndexDefinition("workflows", List.of("depends_on_workflow_id"), "idx_workflows_depends_on",
                    "CREATE INDEX IF NOT EXISTS idx_workflows_depends_on ON workflows (depends_on_workflow_id)"),
            new IndexDefinition("workflow_tasks", List.of("workflow_id", "execution_order"), "idx_workflow_tasks_wf_order",
                    "CREATE INDEX IF NOT EXISTS idx_workflow_tasks_wf_order ON workflow_tasks (workflow_id, execution_order)"),
            new IndexDefinition("workflow_tasks", List.of("status"), "idx_workflow_tasks_status",
                    "CREATE INDEX IF NOT EXISTS idx_workflow_tasks_status ON workflow_tasks (status)"),
            new IndexDefinition("workflow_executions", List.of("workflow_id", "started_at", "id"), "idx_workflow_executions_wf_started",
                    "CREATE INDEX IF NOT EXISTS idx_workflow_executions_wf_started ON workflow_executions (workflow_id, started_at DESC, id DESC)"),
            new IndexDefinition("execution_events", List.of("workflow_id", "timestamp", "id"), "idx_execution_events_wf_ts",
                    "CREATE INDEX IF NOT EXISTS idx_execution_events_wf_ts ON execution_events (workflow_id, timestamp DESC, id DESC)"),
            new IndexDefinition("execution_events", List.of("workflow_id", "task_id", "id"), "idx_execution_events_wf_task_id",
                    "CREATE INDEX IF NOT EXISTS idx_execution_events_wf_task_id ON execution_events (workflow_id, task_id, id DESC)"),
            new IndexDefinition("execution_events", List.of("workflow_id", "task_name", "id"), "idx_execution_events_wf_task_name",
                    "CREATE INDEX IF NOT EXISTS idx_execution_events_wf_task_name ON execution_events (workflow_id, task_name, id DESC)"),
            new IndexDefinition("execution_events", List.of("workflow_id", "event_id"), "idx_execution_events_wf_event_id",
                    "CREATE INDEX IF NOT EXISTS idx_execution_events_wf_event_id ON execution_events (workflow_id, event_id)")
    );

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public DatabaseIndexMigration(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    public DatabaseIndexMigration(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgreSql()) {
            log.debug("Skipping PostgreSQL index migration on non-PostgreSQL datasource");
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + MIGRATION_ADVISORY_LOCK_ID + ")");
                int applied = 0;
                for (IndexDefinition def : INDEX_DEFINITIONS) {
                    if (tableExists(def.tableName()) && columnsExist(def.tableName(), def.requiredColumns())) {
                        jdbcTemplate.execute(def.createSql());
                        applied++;
                    }
                }
                log.info("Verified/created {} query-driven indexes across orchestrator-service tables", applied);
            });
        } catch (Exception ex) {
            log.error("Failed to execute orchestrator-service index migration: {}. Aborting startup.", ex.getMessage());
            throw new IllegalStateException("Failed to execute orchestrator-service index migration: " + ex.getMessage(), ex);
        }
    }

    private boolean isPostgreSql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) conn ->
                "PostgreSQL".equalsIgnoreCase(conn.getMetaData().getDatabaseProductName())
        ));
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = ?
                """,
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private boolean columnsExist(String tableName, List<String> columns) {
        for (String col : columns) {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = ?
                    """,
                    Integer.class,
                    tableName,
                    col
            );
            if (count == null || count == 0) {
                return false;
            }
        }
        return true;
    }
}
