package com.orynx.orchestrator.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowStatusConstraintMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private WorkflowStatusConstraintMigration migration;

    @Test
    @DisplayName("Should skip migration if workflows table does not exist")
    void shouldSkipWhenTableDoesNotExist() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE workflows"));
    }

    @Test
    @DisplayName("Should skip migration if constraint already contains PAUSED and CANCELLED")
    void shouldSkipWhenConstraintAlreadyUpToDate() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows_status_check"))).thenReturn(1);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE workflows"));
    }

    @Test
    @DisplayName("Should drop and recreate constraint if table exists but constraint lacks PAUSED/CANCELLED")
    void shouldMigrateWhenConstraintNeedsUpdate() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows_status_check"))).thenReturn(0);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute("ALTER TABLE workflows DROP CONSTRAINT IF EXISTS workflows_status_check");
        verify(jdbcTemplate).execute(
                "ALTER TABLE workflows ADD CONSTRAINT workflows_status_check " +
                "CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'))"
        );
    }

    @Test
    @DisplayName("Should handle database exception gracefully without terminating application startup")
    void shouldHandleDatabaseExceptionGracefully() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("Database connectivity failure"));

        // Must not throw an unhandled exception
        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE workflows"));
    }
}
