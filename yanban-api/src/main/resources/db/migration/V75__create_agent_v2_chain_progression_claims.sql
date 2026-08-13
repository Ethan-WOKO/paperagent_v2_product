CREATE TABLE agent_v2_chain_progression_claims (
    task_id VARCHAR(128) NOT NULL,
    fence BIGINT NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    claim_token VARCHAR(128) NOT NULL,
    authority_event_cut BIGINT NOT NULL,
    acquired_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    PRIMARY KEY (task_id, fence),
    CONSTRAINT uk_chain_progression_claim_token UNIQUE (claim_token),
    CONSTRAINT fk_chain_progression_claim_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_progression_claim_fence CHECK (fence > 0),
    CONSTRAINT ck_chain_progression_claim_owner
        CHECK (CHAR_LENGTH(TRIM(owner_id)) > 0),
    CONSTRAINT ck_chain_progression_claim_token
        CHECK (CHAR_LENGTH(TRIM(claim_token)) > 0),
    CONSTRAINT ck_chain_progression_claim_cut
        CHECK (authority_event_cut >= 0),
    CONSTRAINT ck_chain_progression_claim_expiry
        CHECK (expires_at > acquired_at),
    CONSTRAINT ck_chain_progression_claim_release
        CHECK (released_at IS NULL OR released_at >= acquired_at)
);

CREATE INDEX idx_chain_progression_claim_task_fence
    ON agent_v2_chain_progression_claims (task_id, fence);

CREATE INDEX idx_chain_command_progression_scan
    ON agent_v2_chain_commands (status, committed_at, result_task_id);
