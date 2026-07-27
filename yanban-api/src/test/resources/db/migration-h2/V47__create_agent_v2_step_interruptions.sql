CREATE TABLE agent_v2_step_interruptions (
    plan_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    interruption_event_id VARCHAR(128) NOT NULL PRIMARY KEY,
    interruption_kind VARCHAR(16) NOT NULL,
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
    request_json CLOB NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json CLOB NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_agent_v2_step_interruptions_plan UNIQUE (plan_id),
    CONSTRAINT ck_agent_v2_step_interruptions_kind
        CHECK (interruption_kind IN ('PAUSE', 'FAIL', 'CANCEL')),
    CONSTRAINT ck_agent_v2_step_interruptions_source
        CHECK (source_revision_number > 0
          AND source_checkpoint_version = 3
          AND source_event_sequence = 2),
    CONSTRAINT ck_agent_v2_step_interruptions_result
        CHECK (result_revision_id = source_revision_id
          AND result_revision_number = source_revision_number
          AND result_checkpoint_version = 4
          AND result_event_sequence = 3),
    CONSTRAINT ck_agent_v2_step_interruptions_owner
        CHECK (CHAR_LENGTH(TRIM(lease_owner_id)) > 0),
    CONSTRAINT ck_agent_v2_step_interruptions_fence CHECK (fencing_token > 0),
    CONSTRAINT ck_agent_v2_step_interruptions_formats
        CHECK (request_format_version = 1 AND result_format_version = 1),
    CONSTRAINT ck_agent_v2_step_interruptions_hashes
        CHECK (REGEXP_LIKE(request_sha256, '^[0-9a-f]{64}$')
          AND REGEXP_LIKE(result_sha256, '^[0-9a-f]{64}$'))
);
