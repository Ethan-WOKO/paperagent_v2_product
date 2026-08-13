CREATE TABLE agent_v2_chain_validation_bundles (
    validation_bundle_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    plan_revision_number BIGINT NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    final_step_id VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    receipt_set_digest CHAR(64) NOT NULL,
    conclusion_digest CHAR(64) NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (validation_bundle_id),
    CONSTRAINT uk_chain_validation_bundle_task_identity
        UNIQUE (task_id, validation_bundle_id),
    CONSTRAINT uk_chain_validation_bundle_event UNIQUE (event_id),
    CONSTRAINT uk_chain_validation_bundle_idempotency
        UNIQUE (task_id, idempotency_key),
    CONSTRAINT fk_chain_validation_bundle_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_validation_bundle_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_validation_bundle_revision
        CHECK (plan_revision_number > 0),
    CONSTRAINT ck_chain_validation_bundle_hashes CHECK (
        REGEXP_LIKE(request_digest, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(receipt_set_digest, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(conclusion_digest, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_chain_validation_bundle_conclusion
        CHECK (conclusion IN ('PASSED','FAILED'))
);

CREATE TABLE agent_v2_chain_validation_bundle_sets (
    validation_bundle_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    validation_id VARCHAR(128) NOT NULL,
    validation_request_digest CHAR(64) NOT NULL,
    validation_receipt_set_digest CHAR(64) NOT NULL,
    validation_conclusion_digest CHAR(64) NOT NULL,
    PRIMARY KEY (validation_bundle_id, step_id),
    CONSTRAINT uk_chain_validation_bundle_set_validation
        UNIQUE (validation_bundle_id, validation_id),
    CONSTRAINT fk_chain_validation_bundle_set_bundle
        FOREIGN KEY (task_id, validation_bundle_id)
        REFERENCES agent_v2_chain_validation_bundles(
                     task_id, validation_bundle_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_validation_bundle_set_validation
        FOREIGN KEY (task_id, validation_id)
        REFERENCES agent_v2_chain_validation_sets(task_id, validation_id),
    CONSTRAINT ck_chain_validation_bundle_set_hashes CHECK (
        REGEXP_LIKE(validation_request_digest, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(validation_receipt_set_digest, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(validation_conclusion_digest, '^[0-9a-f]{64}$'))
);

CREATE INDEX idx_chain_validation_bundle_task
    ON agent_v2_chain_validation_bundles(task_id, created_at);
CREATE INDEX idx_chain_validation_bundle_set_validation
    ON agent_v2_chain_validation_bundle_sets(task_id, validation_id);
