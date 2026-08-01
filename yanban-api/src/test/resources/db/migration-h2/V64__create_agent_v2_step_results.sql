CREATE TABLE agent_v2_step_results (
    result_id VARCHAR(128) PRIMARY KEY,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    source VARCHAR(16) NOT NULL,
    proposed_text CLOB NOT NULL,
    proposed_sha256 CHAR(64) NOT NULL,
    evidence_receipt_ids_json CLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    accepted_text CLOB,
    accepted_sha256 CHAR(64),
    accepted_activation_event_id VARCHAR(128),
    resolution_reason VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_agent_v2_step_result_proposal
        UNIQUE (activation_event_id, source, proposed_sha256),
    CONSTRAINT uk_agent_v2_step_result_accepted_activation
        UNIQUE (accepted_activation_event_id),
    CONSTRAINT ck_agent_v2_step_result_source CHECK
        (source IN ('MODEL','REFLECTION')),
    CONSTRAINT ck_agent_v2_step_result_status CHECK
        (status IN ('PROPOSED','ACCEPTED','REJECTED'))
);

CREATE INDEX idx_agent_v2_step_results_plan_step
    ON agent_v2_step_results(plan_id, step_id, created_at);

CREATE INDEX idx_agent_v2_step_results_activation_status
    ON agent_v2_step_results(activation_event_id, status);
