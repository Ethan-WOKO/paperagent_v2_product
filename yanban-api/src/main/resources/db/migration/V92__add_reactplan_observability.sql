ALTER TABLE reactplan_model_completions
    ADD COLUMN provider_key VARCHAR(64) NULL AFTER response_json,
    ADD COLUMN model_name VARCHAR(128) NULL AFTER provider_key,
    ADD COLUMN request_bytes BIGINT NOT NULL DEFAULT 0 AFTER model_name,
    ADD COLUMN response_bytes BIGINT NOT NULL DEFAULT 0 AFTER request_bytes,
    ADD COLUMN prompt_tokens INT NOT NULL DEFAULT 0 AFTER response_bytes,
    ADD COLUMN completion_tokens INT NOT NULL DEFAULT 0 AFTER prompt_tokens,
    ADD COLUMN replay_count INT NOT NULL DEFAULT 0 AFTER completion_tokens,
    ADD COLUMN error_code VARCHAR(96) NULL AFTER replay_count;

CREATE INDEX idx_reactplan_model_task_created
    ON reactplan_model_completions(task_id, created_at);
