-- Run manually if needed before deploying the migration fix.
-- The application also applies this automatically on startup via
-- WorkflowStatusConstraintMigration.

ALTER TABLE workflows DROP CONSTRAINT IF EXISTS workflows_status_check;

ALTER TABLE workflows
    ADD CONSTRAINT workflows_status_check
    CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'));
