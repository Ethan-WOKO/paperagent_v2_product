ALTER TABLE agent_v2_step_activations
    ADD CONSTRAINT uk_agent_v2_step_activation_binding
        UNIQUE (activation_event_id, plan_id, step_id);

ALTER TABLE agent_v2_effect_results
    ADD CONSTRAINT uk_agent_v2_effect_result_receipt
        UNIQUE (tool_call_id, receipt_id);

CREATE TABLE agent_v2_step_completions (
    completion_event_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    source_revision_id VARCHAR(128) NOT NULL,
    source_revision_number BIGINT NOT NULL,
    result_revision_id VARCHAR(128) NOT NULL,
    result_revision_number BIGINT NOT NULL,
    source_checkpoint_version BIGINT NOT NULL,
    result_checkpoint_version BIGINT NOT NULL,
    source_event_sequence BIGINT NOT NULL,
    result_event_sequence BIGINT NOT NULL,
    lease_owner_id VARCHAR(255) NOT NULL,
    fencing_token BIGINT NOT NULL,
    request_format_version INT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (completion_event_id),
    UNIQUE KEY uk_agent_v2_step_completions_plan (plan_id),
    CONSTRAINT ck_agent_v2_step_completion_versions CHECK (
        source_revision_number > 0
        AND result_revision_number = source_revision_number + 1
        AND source_checkpoint_version = 3
        AND result_checkpoint_version = 4
        AND source_event_sequence = 2
        AND result_event_sequence = 3
        AND fencing_token > 0),
    CONSTRAINT ck_agent_v2_step_completion_formats CHECK (
        request_format_version = 1 AND result_format_version = 1),
    CONSTRAINT ck_agent_v2_step_completion_hashes CHECK (
        request_sha256 REGEXP '^[0-9a-f]{64}$'
        AND result_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT fk_agent_v2_step_completion_activation
        FOREIGN KEY (activation_event_id, plan_id, step_id)
        REFERENCES agent_v2_step_activations
            (activation_event_id, plan_id, step_id)
);

CREATE TABLE agent_v2_step_completion_evidence (
    completion_event_id VARCHAR(128) NOT NULL,
    ordinal INT NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    receipt_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (completion_event_id, ordinal),
    UNIQUE KEY uk_agent_v2_step_completion_tool
        (completion_event_id, tool_call_id),
    CONSTRAINT ck_agent_v2_step_completion_evidence_ordinal
        CHECK (ordinal >= 0),
    CONSTRAINT fk_agent_v2_step_completion_evidence_marker
        FOREIGN KEY (completion_event_id)
        REFERENCES agent_v2_step_completions (completion_event_id),
    CONSTRAINT fk_agent_v2_step_completion_evidence_outcome
        FOREIGN KEY (tool_call_id, receipt_id)
        REFERENCES agent_v2_effect_results (tool_call_id, receipt_id)
);
