package com.orynx.orchestrator.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowOwnershipMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    private WorkflowOwnershipMigration migration;

    @BeforeEach
    void setUp() {
        migration = new WorkflowOwnershipMigration(jdbcTemplate, transactionTemplate);
    }

    @Test
    @DisplayName("Should skip migration if workflows table does not exist (fresh database initialization)")
    void shouldSkipMigrationWhenTableDoesNotExist() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows")))
                .thenReturn(0);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(transactionTemplate, never()).executeWithoutResult(any());
        verify(jdbcTemplate, never()).execute(WorkflowOwnershipMigration.ADD_COLUMN_SQL);
    }

    @Test
    @DisplayName("Should skip migration if user_id column already exists in workflows table")
    void shouldSkipMigrationWhenColumnAlreadyExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows")))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows"), eq("user_id")))
                .thenReturn(1);

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(transactionTemplate, never()).executeWithoutResult(any());
        verify(jdbcTemplate, never()).execute(WorkflowOwnershipMigration.ADD_COLUMN_SQL);
    }

    @Test
    @DisplayName("Should apply migration when workflows table exists and user_id column is missing")
    void shouldApplyMigrationWhenColumnIsMissing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows")))
                .thenReturn(1);
        // First check outside tx: column missing (0), second check inside tx: column missing (0)
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows"), eq("user_id")))
                .thenReturn(0, 0);

        doAnswer(invocation -> {
            Consumer<Object> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute("SELECT pg_advisory_xact_lock(" + WorkflowOwnershipMigration.MIGRATION_ADVISORY_LOCK_ID + ")");
        verify(jdbcTemplate).execute(WorkflowOwnershipMigration.ADD_COLUMN_SQL);
        verify(jdbcTemplate).execute(WorkflowOwnershipMigration.CREATE_INDEX_SQL);
    }

    @Test
    @DisplayName("Should skip DDL execution inside transaction if concurrent instance already added the column")
    void shouldSkipExecutionIfConcurrentInstanceAddedColumn() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows")))
                .thenReturn(1);
        // First check outside tx: 0 (needs migration); second check inside tx after advisory lock: 1 (added by peer)
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows"), eq("user_id")))
                .thenReturn(0, 1);

        doAnswer(invocation -> {
            Consumer<Object> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        migration.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute("SELECT pg_advisory_xact_lock(" + WorkflowOwnershipMigration.MIGRATION_ADVISORY_LOCK_ID + ")");
        verify(jdbcTemplate, never()).execute(WorkflowOwnershipMigration.ADD_COLUMN_SQL);
        verify(jdbcTemplate, never()).execute(WorkflowOwnershipMigration.CREATE_INDEX_SQL);
    }

    @Test
    @DisplayName("Should fail fast with IllegalStateException when migration query fails")
    void shouldFailFastWhenMigrationFails() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workflows")))
                .thenThrow(new RuntimeException("Database connectivity lost"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> migration.run(new DefaultApplicationArguments(new String[0]))
        );

        assertThat(ex.getMessage()).contains("Failed to migrate workflow ownership column");
    }
}
