CREATE TABLE agent_v2_chain_action_receipt_step_blocks (
    step_block_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    receipt_id VARCHAR(128) NOT NULL,
    receipt_payload_sha256 CHAR(64) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    plan_revision_number BIGINT NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    repair_proposal_id VARCHAR(128) NOT NULL,
    repair_context_revision_id VARCHAR(128) NOT NULL,
    repair_proposal_signature_sha256 CHAR(64) NOT NULL,
    progress_authority_event_cut BIGINT NOT NULL,
    progress_snapshot_digest_sha256 CHAR(64) NOT NULL,
    threshold_observed_occurrences INT NOT NULL,
    receipt_status VARCHAR(32) NOT NULL,
    failure_category VARCHAR(64) NOT NULL,
    failure_code VARCHAR(128) NOT NULL,
    block_reason_code VARCHAR(64) NOT NULL,
    runtime_policy_version VARCHAR(64) NOT NULL,
    version_fence_sha256 CHAR(64) NOT NULL,
    block_identity_digest_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (step_block_id),
    CONSTRAINT uk_chain_action_receipt_step_block_action
        UNIQUE (task_id, action_id),
    CONSTRAINT uk_chain_action_receipt_step_block_event UNIQUE (event_id),
    CONSTRAINT fk_chain_action_receipt_step_block_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_receipt_step_block_action
        FOREIGN KEY (task_id, action_id)
        REFERENCES agent_v2_chain_action_bindings(task_id, action_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_receipt_step_block_receipt
        FOREIGN KEY (action_id, receipt_id)
        REFERENCES agent_v2_effect_results(tool_call_id, receipt_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_receipt_step_block_repair_proposal
        FOREIGN KEY (task_id, repair_proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_receipt_step_block_repair_context
        FOREIGN KEY (task_id, repair_context_revision_id)
        REFERENCES agent_v2_chain_context_revisions(task_id, context_revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_receipt_step_block_event FOREIGN KEY (event_id)
        REFERENCES agent_v2_chain_authority_events(event_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_receipt_step_block_instruction
        FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_action_receipt_step_block_plan_revision
        CHECK (plan_revision_number > 0),
    CONSTRAINT ck_chain_action_receipt_step_block_progress
        CHECK (progress_authority_event_cut >= 0
            AND threshold_observed_occurrences > 0),
    CONSTRAINT ck_chain_action_receipt_step_block_status
        CHECK (receipt_status IN ('FAILURE', 'TIMEOUT', 'CANCELLED')),
    CONSTRAINT ck_chain_action_receipt_step_block_category
        CHECK (failure_category = 'EXECUTION'),
    CONSTRAINT ck_chain_action_receipt_step_block_hashes CHECK (
        receipt_payload_sha256 REGEXP '^[0-9a-f]{64}$'
        AND repair_proposal_signature_sha256 REGEXP '^[0-9a-f]{64}$'
        AND progress_snapshot_digest_sha256 REGEXP '^[0-9a-f]{64}$'
        AND version_fence_sha256 REGEXP '^[0-9a-f]{64}$'
        AND block_identity_digest_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_action_receipt_step_block_reason CHECK (
        block_reason_code IN ('NO_PROGRESS_THRESHOLD_REACHED',
            'REPEATED_ACTION_SIGNATURE', 'REPAIR_DID_NOT_CHANGE_ACTION'))
);
