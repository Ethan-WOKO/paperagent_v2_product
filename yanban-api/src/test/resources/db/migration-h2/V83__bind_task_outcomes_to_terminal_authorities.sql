ALTER TABLE agent_v2_chain_finalization_checks
    ADD CONSTRAINT uk_chain_finalization_check_task_identity
        UNIQUE (task_id, finalization_check_id);

ALTER TABLE agent_v2_chain_task_outcomes
    ADD COLUMN finalization_readiness_id VARCHAR(128) NULL;
ALTER TABLE agent_v2_chain_task_outcomes
    ADD COLUMN finalization_check_id VARCHAR(128) NULL;
ALTER TABLE agent_v2_chain_task_outcomes
    ADD COLUMN validation_request_digest CHAR(64) NULL;
ALTER TABLE agent_v2_chain_task_outcomes
    ADD COLUMN validation_receipt_digest CHAR(64) NULL;
ALTER TABLE agent_v2_chain_task_outcomes
    ADD COLUMN publish_requirement VARCHAR(16) NULL;
ALTER TABLE agent_v2_chain_task_outcomes
    ADD COLUMN publish_requirement_digest CHAR(64) NULL;

ALTER TABLE agent_v2_chain_task_outcomes
    ADD CONSTRAINT fk_chain_task_outcome_readiness
        FOREIGN KEY (task_id, finalization_readiness_id)
        REFERENCES agent_v2_chain_finalization_readiness(task_id, readiness_id);
ALTER TABLE agent_v2_chain_task_outcomes
    ADD CONSTRAINT fk_chain_task_outcome_check
        FOREIGN KEY (task_id, finalization_check_id)
        REFERENCES agent_v2_chain_finalization_checks(
                     task_id, finalization_check_id);
ALTER TABLE agent_v2_chain_task_outcomes
    ADD CONSTRAINT ck_chain_task_outcome_terminal_root CHECK (
        (finalization_readiness_id IS NULL
            AND finalization_check_id IS NULL
            AND publish_requirement IS NULL
            AND publish_requirement_digest IS NULL)
        OR
        (finalization_readiness_id IS NOT NULL
            AND finalization_check_id IS NOT NULL
            AND publish_requirement IN ('REQUIRED','NOT_REQUIRED')
            AND publish_requirement_digest IS NOT NULL
            AND REGEXP_LIKE(publish_requirement_digest,
                            '^[0-9a-f]{64}$')));
ALTER TABLE agent_v2_chain_task_outcomes
    ADD CONSTRAINT ck_chain_task_outcome_terminal_validation CHECK (
        (finalization_readiness_id IS NULL
            AND validation_request_digest IS NULL
            AND validation_receipt_digest IS NULL)
        OR
        (finalization_readiness_id IS NOT NULL
            AND ((validation_id = 'NONE'
                    AND validation_request_digest IS NULL
                    AND validation_receipt_digest IS NULL)
                OR
                (validation_id <> 'NONE'
                    AND validation_request_digest IS NOT NULL
                    AND validation_receipt_digest IS NOT NULL
                    AND REGEXP_LIKE(validation_request_digest,
                                    '^[0-9a-f]{64}$')
                    AND REGEXP_LIKE(validation_receipt_digest,
                                    '^[0-9a-f]{64}$')))));
ALTER TABLE agent_v2_chain_task_outcomes
    ADD CONSTRAINT ck_chain_task_outcome_terminal_publish CHECK (
        finalization_readiness_id IS NULL
        OR
        (publish_requirement = 'NOT_REQUIRED'
            AND publish_operation_id IS NULL
            AND published_project_version IS NULL
            AND published_revision_id IS NULL
            AND publish_receipt_id IS NULL)
        OR
        (publish_requirement = 'REQUIRED'
            AND outcome_type <> 'COMPLETED')
        OR
        (publish_requirement = 'REQUIRED'
            AND outcome_type = 'COMPLETED'
            AND publish_operation_id IS NOT NULL
            AND published_project_version IS NOT NULL
            AND published_revision_id IS NOT NULL
            AND publish_receipt_id IS NOT NULL));
