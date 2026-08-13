ALTER TABLE agent_v2_receipts
    ADD CONSTRAINT uk_agent_v2_receipt_action_payload
        UNIQUE (receipt_id, tool_call_id, payload_sha256);

ALTER TABLE agent_v2_chain_action_bindings
    ADD CONSTRAINT uk_chain_action_identity_signature
        UNIQUE (task_id, action_id, action_signature_sha256);

ALTER TABLE agent_v2_chain_workspace_candidates
    ADD CONSTRAINT uk_chain_candidate_action_identity
        UNIQUE (workspace_candidate_id, action_id);

CREATE TABLE agent_v2_chain_validation_sets (
    validation_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    plan_revision_number BIGINT NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    receipt_set_digest CHAR(64) NOT NULL,
    conclusion_digest CHAR(64) NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (validation_id),
    CONSTRAINT uk_chain_validation_set_task_identity
        UNIQUE (task_id, validation_id),
    CONSTRAINT uk_chain_validation_set_event UNIQUE (event_id),
    CONSTRAINT uk_chain_validation_set_idempotency
        UNIQUE (task_id, idempotency_key),
    CONSTRAINT fk_chain_validation_set_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_validation_set_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_validation_set_revision
        CHECK (plan_revision_number > 0),
    CONSTRAINT ck_chain_validation_set_hashes CHECK (
        request_digest REGEXP '^[0-9a-f]{64}$'
        AND receipt_set_digest REGEXP '^[0-9a-f]{64}$'
        AND conclusion_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_validation_set_conclusion
        CHECK (conclusion IN ('PASSED','FAILED'))
);

CREATE TABLE agent_v2_chain_candidate_validation_items (
    validation_id VARCHAR(128) NOT NULL,
    requirement_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    requirement_digest CHAR(64) NOT NULL,
    candidate_action_id VARCHAR(128) NOT NULL,
    validation_action_id VARCHAR(128) NOT NULL,
    receipt_id VARCHAR(128) NOT NULL,
    receipt_payload_sha256 CHAR(64) NOT NULL,
    action_signature_sha256 CHAR(64) NOT NULL,
    workspace_candidate_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    artifact_id BIGINT NOT NULL,
    candidate_fingerprint CHAR(64) NOT NULL,
    base_project_version VARCHAR(255) NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    PRIMARY KEY (validation_id, requirement_id),
    CONSTRAINT fk_chain_candidate_validation_set
        FOREIGN KEY (task_id, validation_id)
        REFERENCES agent_v2_chain_validation_sets(task_id, validation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_candidate_validation_candidate
        FOREIGN KEY (workspace_candidate_id, candidate_action_id)
        REFERENCES agent_v2_chain_workspace_candidates(
                     workspace_candidate_id, action_id),
    CONSTRAINT fk_chain_candidate_validation_candidate_action
        FOREIGN KEY (task_id, candidate_action_id)
        REFERENCES agent_v2_chain_action_bindings(task_id, action_id),
    CONSTRAINT fk_chain_candidate_validation_action
        FOREIGN KEY (task_id, validation_action_id, action_signature_sha256)
        REFERENCES agent_v2_chain_action_bindings(
                     task_id, action_id, action_signature_sha256),
    CONSTRAINT fk_chain_candidate_validation_receipt
        FOREIGN KEY (receipt_id, validation_action_id,
                     receipt_payload_sha256)
        REFERENCES agent_v2_receipts(
                     receipt_id, tool_call_id, payload_sha256),
    CONSTRAINT ck_chain_candidate_validation_hashes CHECK (
        requirement_digest REGEXP '^[0-9a-f]{64}$'
        AND receipt_payload_sha256 REGEXP '^[0-9a-f]{64}$'
        AND action_signature_sha256 REGEXP '^[0-9a-f]{64}$'
        AND candidate_fingerprint REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_candidate_validation_artifact
        CHECK (artifact_id > 0),
    CONSTRAINT ck_chain_candidate_validation_conclusion
        CHECK (conclusion IN ('PASSED','FAILED'))
);

CREATE TABLE agent_v2_chain_action_receipt_validation_items (
    validation_id VARCHAR(128) NOT NULL,
    requirement_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    requirement_digest CHAR(64) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    receipt_id VARCHAR(128) NOT NULL,
    receipt_payload_sha256 CHAR(64) NOT NULL,
    action_signature_sha256 CHAR(64) NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    PRIMARY KEY (validation_id, requirement_id),
    CONSTRAINT fk_chain_action_validation_set
        FOREIGN KEY (task_id, validation_id)
        REFERENCES agent_v2_chain_validation_sets(task_id, validation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_validation_action
        FOREIGN KEY (task_id, action_id, action_signature_sha256)
        REFERENCES agent_v2_chain_action_bindings(
                     task_id, action_id, action_signature_sha256),
    CONSTRAINT fk_chain_action_validation_receipt
        FOREIGN KEY (receipt_id, action_id, receipt_payload_sha256)
        REFERENCES agent_v2_receipts(
                     receipt_id, tool_call_id, payload_sha256),
    CONSTRAINT ck_chain_action_validation_hashes CHECK (
        requirement_digest REGEXP '^[0-9a-f]{64}$'
        AND receipt_payload_sha256 REGEXP '^[0-9a-f]{64}$'
        AND action_signature_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_action_validation_conclusion
        CHECK (conclusion IN ('PASSED','FAILED'))
);

CREATE INDEX idx_chain_validation_set_task
    ON agent_v2_chain_validation_sets(task_id, created_at);
CREATE INDEX idx_chain_candidate_validation_receipt
    ON agent_v2_chain_candidate_validation_items(receipt_id);
CREATE INDEX idx_chain_action_validation_receipt
    ON agent_v2_chain_action_receipt_validation_items(receipt_id);
