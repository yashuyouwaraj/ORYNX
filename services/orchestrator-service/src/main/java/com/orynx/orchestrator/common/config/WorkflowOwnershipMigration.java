package com.orynx.orchestrator.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(2)
@Slf4j
public class WorkflowOwnershipMigration implements ApplicationRunner {

    public static final String COLUMN_NAME = "user_id";
    public static final String TABLE_NAME = "workflows";
    public static final long MIGRATION_ADVISORY_LOCK_ID = 849201850L;

    public static final String ADD_COLUMN_SQL =
            "ALTER TABLE workflows ADD COLUMN IF NOT EXISTS user_id BIGINT";

    public static final String CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_workflows_user_id ON workflows(user_id)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public WorkflowOwnershipMigration(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    public WorkflowOwnershipMigration(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!tableExists()) {
                log.info("{} table not found; skipping workflow ownership migration (fresh initialization)", TABLE_NAME);
                return;
            }

            if (columnExists()) {
                log.debug("Column {}.{} already exists; skipping ownership migration", TABLE_NAME, COLUMN_NAME);
                return;
            }

            transactionTemplate.executeWithoutResult(status -> {
                acquireAdvisoryLock();

                // Re-verify inside transaction after obtaining advisory lock
                if (columnExists()) {
                    log.debug("Column {}.{} was added by a concurrent instance", TABLE_NAME, COLUMN_NAME);
                    return;
                }

                jdbcTemplate.execute(ADD_COLUMN_SQL);
                jdbcTemplate.execute(CREATE_INDEX_SQL);
                log.info("Successfully added ownership column '{}' and index to '{}' table. Legacy records retained safely with NULL ownership.",
                        COLUMN_NAME, TABLE_NAME);
            });
        } catch (Exception ex) {
            log.error("Failed to migrate workflow ownership column: {}. Aborting startup.", ex.getMessage());
            throw new IllegalStateException("Failed to migrate workflow ownership column: " + ex.getMessage(), ex);
        }
    }

    private void acquireAdvisoryLock() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + MIGRATION_ADVISORY_LOCK_ID + ")");
        } catch (Exception ex) {
            log.debug("Advisory lock not acquired (non-PostgreSQL dialect or permissions): {}", ex.getMessage());
        }
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = ?
                """,
                Integer.class,
                TABLE_NAME
        );
        return count != null && count > 0;
    }

    private boolean columnExists() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """,
                Integer.class,
                TABLE_NAME,
                COLUMN_NAME
        );
        return count != null && count > 0;
    }
}
