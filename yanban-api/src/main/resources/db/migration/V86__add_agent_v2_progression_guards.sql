CREATE TABLE agent_v2_chain_progression_guards (
    task_id VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'RUNNABLE',
    last_failure_sha256 CHAR(64) NULL,
    last_failure_authority_cut BIGINT NULL,
    consecutive_failure_count INT NOT NULL DEFAULT 0,
    reason_code VARCHAR(512) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT fk_chain_progression_guard_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_progression_guard_state CHECK (
        state IN ('RUNNABLE','BLOCKED','CANCELLED')),
    CONSTRAINT ck_chain_progression_guard_failure_count CHECK (
        consecutive_failure_count >= 0),
    CONSTRAINT ck_chain_progression_guard_failure_pair CHECK (
        (last_failure_sha256 IS NULL
         AND last_failure_authority_cut IS NULL
         AND consecutive_failure_count = 0)
        OR
        (last_failure_sha256 IS NOT NULL
         AND last_failure_authority_cut IS NOT NULL
         AND consecutive_failure_count > 0))
);

CREATE INDEX idx_chain_progression_guard_state
    ON agent_v2_chain_progression_guards(state, updated_at);
