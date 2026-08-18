CREATE TABLE reactplan_conversation_summaries (
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    summary_text LONGTEXT,
    covered_intake_id BIGINT NOT NULL DEFAULT 0,
    target_intake_id BIGINT NOT NULL DEFAULT 0,
    covered_turn_count INT NOT NULL DEFAULT 0,
    model_provider_snapshot VARCHAR(64),
    model_snapshot VARCHAR(128),
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMP NULL,
    last_error VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id),
    INDEX idx_reactplan_summary_work (state, lease_expires_at, updated_at),
    INDEX idx_reactplan_summary_user (user_id, updated_at),
    CONSTRAINT fk_reactplan_summary_session FOREIGN KEY (session_id)
        REFERENCES agent_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_reactplan_summary_user FOREIGN KEY (user_id)
        REFERENCES sys_users (id)
);
