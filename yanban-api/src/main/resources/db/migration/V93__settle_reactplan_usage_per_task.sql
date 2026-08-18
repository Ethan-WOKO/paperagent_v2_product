ALTER TABLE reactplan_task_checkpoints
    ADD COLUMN usage_settled BOOLEAN NOT NULL DEFAULT FALSE AFTER updated_at,
    ADD COLUMN settled_prompt_tokens BIGINT NOT NULL DEFAULT 0 AFTER usage_settled,
    ADD COLUMN settled_completion_tokens BIGINT NOT NULL DEFAULT 0 AFTER settled_prompt_tokens;

-- Calls belonging to tasks created before this migration were already charged per model call.
-- Mark every existing task as settled so deployment cannot charge historical usage twice.
UPDATE reactplan_task_checkpoints SET usage_settled = TRUE;
