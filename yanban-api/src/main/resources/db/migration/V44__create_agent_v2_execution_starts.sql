CREATE TABLE agent_v2_execution_starts (
    plan_id VARCHAR(128) NOT NULL,
    start_event_id VARCHAR(128) NOT NULL,
    lease_owner_id VARCHAR(255) NOT NULL,
    fencing_token BIGINT NOT NULL,
    request_format_version INT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (plan_id),
    CONSTRAINT uk_agent_v2_execution_starts_event UNIQUE (start_event_id),
    CONSTRAINT fk_agent_v2_execution_starts_bootstrap
        FOREIGN KEY (plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id),
    CONSTRAINT ck_agent_v2_execution_starts_owner
        CHECK (CHAR_LENGTH(TRIM(lease_owner_id)) > 0),
    CONSTRAINT ck_agent_v2_execution_starts_fence CHECK (fencing_token > 0),
    CONSTRAINT ck_agent_v2_execution_starts_request_format
        CHECK (request_format_version = 1),
    CONSTRAINT ck_agent_v2_execution_starts_result_format
        CHECK (result_format_version = 1),
    CONSTRAINT ck_agent_v2_execution_starts_request_sha256
        CHECK (request_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_v2_execution_starts_result_sha256
        CHECK (result_sha256 REGEXP '^[0-9a-f]{64}$')
);
