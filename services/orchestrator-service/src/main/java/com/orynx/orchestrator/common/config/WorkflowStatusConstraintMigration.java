package com.orynx.orchestrator.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class WorkflowStatusConstraintMigration implements ApplicationRunner {

    private static final String CONSTRAINT_NAME = "workflows_status_check";
    private static final String VALID_STATUSES =
            "('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!tableExists()) {
                log.debug("workflows table not found; skipping status constraint migration");
                return;
            }

            if (constraintIncludesPausedAndCancelled()) {
                log.debug("{} already allows PAUSED and CANCELLED statuses", CONSTRAINT_NAME);
                return;
            }

            jdbcTemplate.execute(
                    "ALTER TABLE workflows DROP CONSTRAINT IF EXISTS " + CONSTRAINT_NAME
            );
            jdbcTemplate.execute(
                    "ALTER TABLE workflows ADD CONSTRAINT " + CONSTRAINT_NAME
                            + " CHECK (status IN " + VALID_STATUSES + ")"
            );
            log.info("Updated {} to allow PAUSED and CANCELLED workflow statuses", CONSTRAINT_NAME);
        } catch (Exception ex) {
            log.warn("Could not migrate workflows status constraint: {}", ex.getMessage());
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
