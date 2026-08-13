ALTER TABLE agent_v2_chain_action_receipt_step_blocks
    ADD COLUMN failure_authority_type VARCHAR(64) NULL AFTER action_id,
    ADD COLUMN failure_authority_ref VARCHAR(128) NULL AFTER failure_authority_type;

UPDATE agent_v2_chain_action_receipt_step_blocks
   SET failure_authority_type = 'RECEIPT',
       failure_authority_ref = receipt_id;

ALTER TABLE agent_v2_chain_action_receipt_step_blocks
    MODIFY failure_authority_type VARCHAR(64) NOT NULL,
    MODIFY failure_authority_ref VARCHAR(128) NOT NULL,
    MODIFY receipt_id VARCHAR(128) NULL,
    MODIFY receipt_payload_sha256 CHAR(64) NULL,
    MODIFY receipt_status VARCHAR(32) NULL,
    DROP CHECK ck_chain_action_receipt_step_block_status,
    DROP CHECK ck_chain_action_receipt_step_block_category;

ALTER TABLE agent_v2_chain_action_receipt_step_blocks
    ADD CONSTRAINT ck_chain_action_failure_step_block_source CHECK (
        (failure_authority_type = 'RECEIPT'
            AND failure_authority_ref = receipt_id
            AND receipt_id IS NOT NULL
            AND receipt_payload_sha256 IS NOT NULL
            AND receipt_status IN ('FAILURE', 'TIMEOUT', 'CANCELLED')
            AND failure_category = 'EXECUTION')
        OR
        (failure_authority_type = 'CANDIDATE_MATERIALIZATION_FAILURE'
            AND receipt_id IS NULL
            AND receipt_payload_sha256 IS NULL
            AND receipt_status IS NULL
            AND failure_category = 'CANDIDATE'
            AND failure_code IN (
                'CANDIDATE_REPLACEMENT_BUNDLE_INVALID',
                'CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS',
                'CANDIDATE_REPLACEMENT_TOO_LARGE',
                'CANDIDATE_NO_ACTUAL_CHANGE')));
