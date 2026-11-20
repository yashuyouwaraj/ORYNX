-- Migration: Add user_id column to workflows table for ownership and authorization
-- The application also applies this automatically on startup via WorkflowOwnershipMigration.

-- Acquire transaction-level advisory lock to serialize concurrent migrations
SELECT pg_advisory_xact_lock(849201850);

ALTER TABLE workflows ADD COLUMN IF NOT EXISTS user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_workflows_user_id ON workflows(user_id);
