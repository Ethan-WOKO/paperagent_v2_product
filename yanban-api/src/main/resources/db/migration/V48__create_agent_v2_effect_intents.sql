CREATE TABLE agent_v2_effect_intents (
    tool_call_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    intent_kind VARCHAR(128) NOT NULL,
    lease_owner_id VARCHAR(255) NOT NULL,
    fencing_token BIGINT NOT NULL,
    request_format_version INT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tool_call_id),
    KEY idx_agent_v2_effect_intents_plan_step (plan_id, step_id),
    CONSTRAINT fk_agent_v2_effect_intents_bootstrap
        FOREIGN KEY (plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id),
    CONSTRAINT ck_agent_v2_effect_intents_ids
        CHECK (CHAR_LENGTH(TRIM(tool_call_id)) > 0
          AND CHAR_LENGTH(TRIM(step_id)) > 0
          AND CHAR_LENGTH(TRIM(activation_event_id)) > 0
          AND CHAR_LENGTH(TRIM(intent_kind)) > 0),
    CONSTRAINT ck_agent_v2_effect_intents_owner
        CHECK (CHAR_LENGTH(TRIM(lease_owner_id)) > 0),
    CONSTRAINT ck_agent_v2_effect_intents_fence CHECK (fencing_token > 0),
    CONSTRAINT ck_agent_v2_effect_intents_formats
        CHECK (request_format_version = 1 AND result_format_version = 1),
    CONSTRAINT ck_agent_v2_effect_intents_hashes
        CHECK (request_sha256 REGEXP '^[0-9a-f]{64}$'
          AND result_sha256 REGEXP '^[0-9a-f]{64}$')
);
