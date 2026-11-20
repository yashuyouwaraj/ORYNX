package com.orynx.orchestrator.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowStatusConstraintMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RecordingTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private WorkflowStatusConstraintMigration migration;

    @BeforeEach
    void setUp() {
        transactionManager = new RecordingTransactionManager();
        transactionTemplate = new TransactionTemplate(transactionManager);
        migration = new WorkflowStatusConstraintMigration(jdbcTemplate, transactionTemplate);
    }

    @Test
    @DisplayName("Should skip migration if workflows table does not exist (fresh initialization)")
    void shouldSkipWhenTableDoesNotExist() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE workflows"));
        assertThat(transactionManager.committed).isFalse();
        assertThat(transactionManager.rolledBack).isFalse();
    }

    @Test
    @DisplayName("Should skip migration if constraint already contains PAUSED and CANCELLED")
    void shouldSkipWhenConstraintAlreadyUpToDate() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows_status_check"))).thenReturn(1);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE workflows"));
        assertThat(transactionManager.committed).isFalse();
        assertThat(transactionManager.rolledBack).isFalse();
    }

    @Test
    @DisplayName("Should atomically update constraint in single DDL when table exists but constraint lacks PAUSED/CANCELLED")
    void shouldMigrateAtomicallyWhenConstraintNeedsUpdate() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        // Initial check returns 0, recheck inside transaction returns 0
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows_status_check"))).thenReturn(0, 0);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute(contains("pg_advisory_xact_lock"));
        verify(jdbcTemplate).execute(WorkflowStatusConstraintMigration.ATOMIC_MIGRATION_SQL);
        assertThat(transactionManager.committed).isTrue();
        assertThat(transactionManager.rolledBack).isFalse();
    }

    @Test
    @DisplayName("Should recheck constraint inside transaction and skip DDL if updated concurrently by another instance")
    void shouldRecheckConstraintInsideTransactionAndSkipIfAlreadyUpdatedByConcurrentInstance() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        // Initial check returns 0, but after acquiring advisory lock, another instance has updated it (returns 1)
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows_status_check"))).thenReturn(0, 1);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute(contains("pg_advisory_xact_lock"));
        verify(jdbcTemplate, never()).execute(WorkflowStatusConstraintMigration.ATOMIC_MIGRATION_SQL);
        assertThat(transactionManager.committed).isTrue();
        assertThat(transactionManager.rolledBack).isFalse();
    }

    @Test
    @DisplayName("Should rollback transaction and throw IllegalStateException when DDL execution fails")
    void shouldRollbackAndThrowIllegalStateExceptionWhenMigrationFails() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows_status_check"))).thenReturn(0, 0);
        doThrow(new RuntimeException("DDL lock timeout or invalid table status"))
                .when(jdbcTemplate).execute(WorkflowStatusConstraintMigration.ATOMIC_MIGRATION_SQL);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> migration.run(new DefaultApplicationArguments(new String[0]))
        );

        assertThat(ex.getMessage()).contains("Failed to migrate workflows status constraint");
        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(transactionManager.committed).isFalse();
    }

    @Test
    @DisplayName("Should fail startup when initial database inspection throws connectivity exception")
    void shouldFailStartupWhenDatabaseConnectionFailsOnInitialCheck() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("Database connectivity failure"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> migration.run(new DefaultApplicationArguments(new String[0]))
        );

        assertThat(ex.getMessage()).contains("Database connectivity failure");
        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE workflows"));
        assertThat(transactionManager.committed).isFalse();
    }

    @Test
    @DisplayName("Should be idempotent across multiple run invocations")
    void shouldBeIdempotentAcrossMultipleRuns() {
        // Run 1: Table exists, needs migration
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows_status_check")))
                .thenReturn(0, 0) // First run (initial + inside tx)
                .thenReturn(1);    // Second run (initial check finds it updated)

        migration.run(new DefaultApplicationArguments(new String[0]));
        assertThat(transactionManager.committed).isTrue();

        // Reset spy flags for second run
        transactionManager.committed = false;

        // Run 2: Table exists, already updated
        migration.run(new DefaultApplicationArguments(new String[0]));
        assertThat(transactionManager.committed).isFalse();

        // Verify ATOMIC_MIGRATION_SQL was only called once in total
        verify(jdbcTemplate, times(1)).execute(WorkflowStatusConstraintMigration.ATOMIC_MIGRATION_SQL);
    }

    /**
     * Test transaction manager that records commit and rollback signals
     * to verify transaction lifecycle without requiring an active database.
     */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed = false;
        boolean rolledBack = false;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            committed = false;
            rolledBack = false;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
        }
    }
}
