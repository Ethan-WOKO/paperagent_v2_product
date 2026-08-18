ALTER TABLE reactplan_task_checkpoints
    ADD COLUMN lease_owner VARCHAR(128) NULL;
ALTER TABLE reactplan_task_checkpoints
    ADD COLUMN lease_token VARCHAR(64) NULL;
ALTER TABLE reactplan_task_checkpoints
    ADD COLUMN lease_fence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reactplan_task_checkpoints
    ADD COLUMN lease_expires_at DATETIME(6) NULL;
ALTER TABLE reactplan_task_checkpoints
    ADD COLUMN cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_reactplan_checkpoint_dispatch
    ON reactplan_task_checkpoints(state, lease_expires_at, created_at);

CREATE INDEX idx_reactplan_checkpoint_user_dispatch
    ON reactplan_task_checkpoints(user_id, state, lease_expires_at);

CREATE TABLE reactplan_agent_scheduler_lock (
    lock_id TINYINT NOT NULL,
    PRIMARY KEY (lock_id)
);

INSERT INTO reactplan_agent_scheduler_lock(lock_id) VALUES (1);
