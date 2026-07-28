CREATE TABLE agent_v2_active_step_replans (
    plan_id VARCHAR(128) NOT NULL,
    superseded_step_id VARCHAR(128) NOT NULL,
    supersession_event_id VARCHAR(128) NOT NULL,
    replan_event_id VARCHAR(128) NOT NULL,
    source_revision_id VARCHAR(128) NOT NULL,
    source_revision_number BIGINT NOT NULL,
    result_revision_id VARCHAR(128) NOT NULL,
    result_revision_number BIGINT NOT NULL,
    source_checkpoint_version BIGINT NOT NULL,
    superseded_checkpoint_version BIGINT NOT NULL,
    result_checkpoint_version BIGINT NOT NULL,
    source_event_sequence BIGINT NOT NULL,
    supersession_event_sequence BIGINT NOT NULL,
    result_event_sequence BIGINT NOT NULL,
    lease_owner_id VARCHAR(255) NOT NULL,
    fencing_token BIGINT NOT NULL,
    request_format_version INT NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 VARCHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (supersession_event_id),
    CONSTRAINT uk_agent_v2_replan_event
        UNIQUE (replan_event_id)
);

CREATE INDEX idx_agent_v2_active_step_replans_step
    ON agent_v2_active_step_replans (plan_id, superseded_step_id);

CREATE INDEX idx_agent_v2_active_step_replans_source
    ON agent_v2_active_step_replans (plan_id, source_event_sequence);
