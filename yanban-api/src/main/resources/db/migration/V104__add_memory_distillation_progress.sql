ALTER TABLE agent_memory_distillation_jobs
    ADD COLUMN processed_through_message_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_memory_distillation_jobs
    ADD COLUMN processed_message_count INT NOT NULL DEFAULT 0;
ALTER TABLE agent_memory_distillation_jobs
    ADD COLUMN batch_attempt_count INT NOT NULL DEFAULT 0;

UPDATE agent_memory_distillation_jobs
SET processed_through_message_id = CASE WHEN status = 'SUCCEEDED' THEN through_message_id ELSE from_message_id END,
    processed_message_count = CASE WHEN status = 'SUCCEEDED' THEN message_count ELSE 0 END,
    batch_attempt_count = attempt_count;
