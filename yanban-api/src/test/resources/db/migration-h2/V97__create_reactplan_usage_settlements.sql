CREATE TABLE reactplan_usage_settlements (
    task_id VARCHAR(69) NOT NULL,
    user_id BIGINT NOT NULL,
    prompt_tokens BIGINT NOT NULL,
    completion_tokens BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT ck_reactplan_usage_settlement_state CHECK (
        state IN ('PENDING', 'SETTLED'))
);

CREATE INDEX idx_reactplan_usage_settlement_pending
    ON reactplan_usage_settlements(state, created_at);
