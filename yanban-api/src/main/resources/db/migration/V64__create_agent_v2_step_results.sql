CREATE TABLE agent_v2_step_results (
    result_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    source VARCHAR(16) NOT NULL,
    proposed_text LONGTEXT NOT NULL,
    proposed_sha256 CHAR(64) NOT NULL,
    evidence_receipt_ids_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    accepted_text LONGTEXT NULL,
    accepted_sha256 CHAR(64) NULL,
    accepted_activation_event_id VARCHAR(128) NULL,
    resolution_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (result_id),
    UNIQUE KEY uk_agent_v2_step_result_proposal
        (activation_event_id, source, proposed_sha256),
    UNIQUE KEY uk_agent_v2_step_result_accepted_activation
        (accepted_activation_event_id),
    INDEX idx_agent_v2_step_results_plan_step
        (plan_id, step_id, created_at),
    INDEX idx_agent_v2_step_results_activation_status
        (activation_event_id, status),
    CONSTRAINT ck_agent_v2_step_result_source CHECK
        (source IN ('MODEL','REFLECTION')),
    CONSTRAINT ck_agent_v2_step_result_status CHECK
        (status IN ('PROPOSED','ACCEPTED','REJECTED'))
);
