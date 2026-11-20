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
@Order(1)
@Slf4j
public class WorkflowStatusConstraintMigration implements ApplicationRunner {

    public static final String CONSTRAINT_NAME = "workflows_status_check";
    public static final String VALID_STATUSES =
            "('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')";
    public static final long MIGRATION_ADVISORY_LOCK_ID = 849201849L;

    public static final String ATOMIC_MIGRATION_SQL =
            "ALTER TABLE workflows " +
            "DROP CONSTRAINT IF EXISTS " + CONSTRAINT_NAME + ", " +
            "ADD CONSTRAINT " + CONSTRAINT_NAME + " CHECK (status IN " + VALID_STATUSES + ")";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public WorkflowStatusConstraintMigration(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    public WorkflowStatusConstraintMigration(
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
                log.info("workflows table not found; skipping status constraint migration (fresh database initialization)");
                return;
            }

            if (constraintIncludesPausedAndCancelled()) {
                log.debug("{} already allows PAUSED and CANCELLED statuses", CONSTRAINT_NAME);
                return;
            }

            transactionTemplate.executeWithoutResult(status -> {
                acquireAdvisoryLock();

                // Re-verify inside transaction after obtaining advisory lock
                if (constraintIncludesPausedAndCancelled()) {
                    log.debug("{} already updated by a concurrent instance", CONSTRAINT_NAME);
                    return;
                }

                jdbcTemplate.execute(ATOMIC_MIGRATION_SQL);
                log.info("Atomically updated {} to allow PAUSED and CANCELLED workflow statuses", CONSTRAINT_NAME);
            });
        } catch (Exception ex) {
            log.error("Failed to migrate {} constraint: {}. Aborting application startup.", CONSTRAINT_NAME, ex.getMessage());
            throw new IllegalStateException("Failed to migrate workflows status constraint: " + ex.getMessage(), ex);
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
                  AND table_name = 'workflows'
                """,
                Integer.class
        );
        return count != null && count > 0;
    }

    private boolean constraintIncludesPausedAndCancelled() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                WHERE t.relname = 'workflows'
                  AND c.conname = ?
                  AND pg_get_constraintdef(c.oid) LIKE '%PAUSED%'
                  AND pg_get_constraintdef(c.oid) LIKE '%CANCELLED%'
                """,
                Integer.class,
                CONSTRAINT_NAME
        );
        return count != null && count > 0;
    }
}
