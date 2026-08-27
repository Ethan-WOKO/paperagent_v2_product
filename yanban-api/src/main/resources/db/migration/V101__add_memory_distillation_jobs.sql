CREATE TABLE agent_memory_distillation_settings (
    user_id BIGINT NOT NULL,
    auto_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_processed_message_id BIGINT NOT NULL DEFAULT 0,
    next_run_at TIMESTAMP(6) NULL,
    last_success_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    INDEX idx_memory_distillation_settings_due (auto_enabled, next_run_at),
    CONSTRAINT fk_memory_distillation_settings_user
        FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

CREATE TABLE agent_memory_distillation_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    from_message_id BIGINT NOT NULL,
    through_message_id BIGINT NOT NULL,
    message_count INT NOT NULL DEFAULT 0,
    candidate_count INT NOT NULL DEFAULT 0,
    created_memory_count INT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    claimed_until TIMESTAMP(6) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_memory_distillation_jobs_user_created (user_id, created_at, id),
    INDEX idx_memory_distillation_jobs_claim (status, claimed_until, id),
    CONSTRAINT fk_memory_distillation_jobs_user
        FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

CREATE UNIQUE INDEX uk_ltm_distilled_source
    ON agent_long_term_memories (user_id, source_type, source_ref_id);
