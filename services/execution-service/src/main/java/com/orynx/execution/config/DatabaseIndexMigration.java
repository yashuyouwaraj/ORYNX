package com.orynx.execution.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotently provisions execution-service coordination index for abandoned
 * lease scanning on existing PostgreSQL schemas.
 */
@Component
@Slf4j
public class DatabaseIndexMigration implements ApplicationRunner {

    public static final String TABLE_NAME = "workflow_execution_coordination";
    public static final String CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_exec_coord_status_lease ON workflow_execution_coordination (status, lease_expires_at)";
    public static final long MIGRATION_ADVISORY_LOCK_ID = 849201852L;

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
                if (count != null && count > 0) {
                    jdbcTemplate.execute(CREATE_INDEX_SQL);
                    log.info("Verified/created idx_exec_coord_status_lease on {}", TABLE_NAME);
                }
            });
        } catch (Exception ex) {
            log.error("Failed to execute execution-service index migration: {}. Aborting startup.", ex.getMessage());
            throw new IllegalStateException("Failed to execute execution-service index migration: " + ex.getMessage(), ex);
        }
    }

    private boolean isPostgreSql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) conn ->
                "PostgreSQL".equalsIgnoreCase(conn.getMetaData().getDatabaseProductName())
        ));
    }
}
