-- Run manually if needed before deploying the migration fix.
-- The application also applies this automatically on startup via
-- WorkflowTaskStatusConstraintMigration.

ALTER TABLE workflow_tasks DROP CONSTRAINT IF EXISTS workflow_tasks_status_check;

ALTER TABLE workflow_tasks
    ADD CONSTRAINT workflow_tasks_status_check
    CHECK (status IN ('PENDING', 'RUNNING', 'RETRYING', 'COMPLETED', 'FAILED'));
