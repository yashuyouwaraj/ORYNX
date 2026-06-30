package com.orynx.orchestrator.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowTaskStatusConstraintMigration implements ApplicationRunner {

    private static final String CONSTRAINT_NAME = "workflow_tasks_status_check";
    private static final String VALID_STATUSES =
            "('PENDING', 'RUNNING', 'RETRYING', 'COMPLETED', 'FAILED')";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!tableExists()) {
                log.debug("workflow_tasks table not found; skipping status constraint migration");
                return;
            }

            if (constraintIncludesRetrying()) {
                log.debug("{} already allows RETRYING status", CONSTRAINT_NAME);
                return;
            }

            jdbcTemplate.execute(
                    "ALTER TABLE workflow_tasks DROP CONSTRAINT IF EXISTS " + CONSTRAINT_NAME
            );
            jdbcTemplate.execute(
                    "ALTER TABLE workflow_tasks ADD CONSTRAINT " + CONSTRAINT_NAME
                            + " CHECK (status IN " + VALID_STATUSES + ")"
            );
            log.info("Updated {} to allow RETRYING task status", CONSTRAINT_NAME);
        } catch (Exception ex) {
            log.warn("Could not migrate workflow_tasks status constraint: {}", ex.getMessage());
        }
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'workflow_tasks'
                """,
                Integer.class
        );
        return count != null && count > 0;
    }

    private boolean constraintIncludesRetrying() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                WHERE t.relname = 'workflow_tasks'
                  AND c.conname = ?
                  AND pg_get_constraintdef(c.oid) LIKE '%RETRYING%'
                """,
                Integer.class,
                CONSTRAINT_NAME
        );
        return count != null && count > 0;
    }
}
