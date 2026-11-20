-- Migration: Atomically update workflows_status_check constraint to include PAUSED and CANCELLED
-- Run manually if needed before deploying the migration fix.
-- The application also applies this automatically on startup via WorkflowStatusConstraintMigration.

BEGIN;

-- Acquire transaction-level advisory lock to serialize concurrent migrations
SELECT pg_advisory_xact_lock(849201849);

-- Atomically drop the outdated constraint and add the new constraint in a single DDL statement
ALTER TABLE workflows
    DROP CONSTRAINT IF EXISTS workflows_status_check,
    ADD CONSTRAINT workflows_status_check
    CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'));

COMMIT;
