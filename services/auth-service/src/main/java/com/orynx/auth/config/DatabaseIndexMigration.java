package com.orynx.auth.config;

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
 * Idempotently provisions auth-service user email lookup index on existing
 * PostgreSQL schemas where Hibernate ddl-auto=update does not retrofit @Index
 * annotations onto pre-existing tables.
 */
@Component
@Slf4j
public class DatabaseIndexMigration implements ApplicationRunner {

    public static final String TABLE_NAME = "users";
    public static final String COLUMN_NAME = "email";
    public static final String INDEX_NAME = "idx_users_email";
    public static final String CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_users_email ON users (email)";
    public static final long MIGRATION_ADVISORY_LOCK_ID = 849201853L;

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
                if (tableExists() && columnExists()) {
                    jdbcTemplate.execute(CREATE_INDEX_SQL);
                    log.info("Verified/created {} on {}", INDEX_NAME, TABLE_NAME);
                }
            });
        } catch (Exception ex) {
            log.error("Failed to execute auth-service index migration: {}. Aborting startup.", ex.getMessage());
            throw new IllegalStateException("Failed to execute auth-service index migration: " + ex.getMessage(), ex);
        }
    }

    private boolean isPostgreSql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) conn ->
                "PostgreSQL".equalsIgnoreCase(conn.getMetaData().getDatabaseProductName())
        ));
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
