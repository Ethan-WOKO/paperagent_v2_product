ALTER TABLE sandbox_executions
    ADD COLUMN user_id BIGINT NULL;

CREATE INDEX idx_sandbox_execution_user_claim
    ON sandbox_executions(user_id, status, lease_expires_at);
