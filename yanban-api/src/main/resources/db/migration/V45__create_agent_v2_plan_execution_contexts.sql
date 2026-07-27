CREATE TABLE agent_v2_plan_execution_contexts (
    plan_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    reservation_lease_owner_id VARCHAR(255) NOT NULL,
    reservation_fencing_token BIGINT NOT NULL,
    reservation_request_format_version INT NOT NULL,
    reservation_request_sha256 CHAR(64) NOT NULL,
    reservation_request_json LONGTEXT NOT NULL,
    reservation_result_format_version INT NOT NULL,
    reservation_result_sha256 CHAR(64) NOT NULL,
    reservation_result_json LONGTEXT NOT NULL,
    confirmation_lease_owner_id VARCHAR(255) NULL,
    confirmation_fencing_token BIGINT NULL,
    confirmation_request_format_version INT NULL,
    confirmation_request_sha256 CHAR(64) NULL,
    confirmation_request_json LONGTEXT NULL,
    confirmation_result_format_version INT NULL,
    confirmation_result_sha256 CHAR(64) NULL,
    confirmation_result_json LONGTEXT NULL,
    source_manifest_fingerprint CHAR(64) NULL,
    PRIMARY KEY (plan_id),
    CONSTRAINT uk_agent_v2_plan_execution_contexts_workspace
        UNIQUE (workspace_id),
    CONSTRAINT fk_agent_v2_plan_execution_contexts_bootstrap
        FOREIGN KEY (plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id),
    CONSTRAINT ck_agent_v2_plan_execution_contexts_reservation_owner
        CHECK (CHAR_LENGTH(TRIM(reservation_lease_owner_id)) > 0),
    CONSTRAINT ck_agent_v2_plan_execution_contexts_reservation_fence
        CHECK (reservation_fencing_token > 0),
    CONSTRAINT ck_agent_v2_plan_execution_contexts_reservation_formats
        CHECK (reservation_request_format_version = 1
          AND reservation_result_format_version = 1),
    CONSTRAINT ck_agent_v2_plan_execution_contexts_reservation_hashes
        CHECK (reservation_request_sha256 REGEXP '^[0-9a-f]{64}$'
          AND reservation_result_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_v2_plan_execution_contexts_confirmation_cut
        CHECK (
          (confirmation_lease_owner_id IS NULL
            AND confirmation_fencing_token IS NULL
            AND confirmation_request_format_version IS NULL
            AND confirmation_request_sha256 IS NULL
            AND confirmation_request_json IS NULL
            AND confirmation_result_format_version IS NULL
            AND confirmation_result_sha256 IS NULL
            AND confirmation_result_json IS NULL
            AND source_manifest_fingerprint IS NULL)
          OR
          (CHAR_LENGTH(TRIM(confirmation_lease_owner_id)) > 0
            AND confirmation_fencing_token > 0
            AND confirmation_request_format_version = 1
            AND confirmation_request_sha256 REGEXP '^[0-9a-f]{64}$'
            AND confirmation_request_json IS NOT NULL
            AND confirmation_result_format_version = 1
            AND confirmation_result_sha256 REGEXP '^[0-9a-f]{64}$'
            AND confirmation_result_json IS NOT NULL
            AND source_manifest_fingerprint REGEXP '^[0-9a-f]{64}$')
        )
);
