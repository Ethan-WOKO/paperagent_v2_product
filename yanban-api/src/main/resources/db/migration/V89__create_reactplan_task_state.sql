CREATE TABLE reactplan_task_checkpoints (
    task_id VARCHAR(69) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    last_sequence BIGINT NOT NULL,
    checkpoint_revision BIGINT NOT NULL,
    checkpoint_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT fk_reactplan_checkpoint_session FOREIGN KEY (session_id)
        REFERENCES agent_sessions(id) ON DELETE CASCADE,
    CONSTRAINT ck_reactplan_checkpoint_state CHECK (
        state IN ('queued','running','waiting_user','succeeded','failed','cancelled'))
);

CREATE INDEX idx_reactplan_checkpoint_recovery
    ON reactplan_task_checkpoints(state, updated_at);

CREATE TABLE reactplan_task_events (
    task_id VARCHAR(69) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_json LONGTEXT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id, sequence_number),
    CONSTRAINT fk_reactplan_event_checkpoint FOREIGN KEY (task_id)
        REFERENCES reactplan_task_checkpoints(task_id) ON DELETE CASCADE
);
