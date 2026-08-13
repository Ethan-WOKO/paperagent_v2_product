CREATE TABLE agent_v2_chain_model_failure_step_blocks (
    step_block_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    context_revision_id VARCHAR(128) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    plan_revision_number BIGINT NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    last_provider_attempt_ref VARCHAR(160) NOT NULL,
    failure_category VARCHAR(64) NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    version_fence_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (step_block_id),
    CONSTRAINT uk_chain_model_failure_step_block_invocation
        UNIQUE (task_id, invocation_id),
    CONSTRAINT uk_chain_model_failure_step_block_event UNIQUE (event_id),
    CONSTRAINT fk_chain_model_failure_step_block_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_model_failure_step_block_invocation
        FOREIGN KEY (task_id, invocation_id)
        REFERENCES agent_v2_chain_model_invocations(task_id, invocation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_model_failure_step_block_context
        FOREIGN KEY (task_id, context_revision_id)
        REFERENCES agent_v2_chain_context_revisions(task_id, context_revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_model_failure_step_block_event FOREIGN KEY (event_id)
        REFERENCES agent_v2_chain_authority_events(event_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_model_failure_step_block_instruction
        FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_model_failure_step_block_plan_revision
        CHECK (plan_revision_number > 0),
    CONSTRAINT ck_chain_model_failure_step_block_kind CHECK (
        failure_category = 'MODEL' AND failure_code = 'MODEL_CALL_FAILED')
);
