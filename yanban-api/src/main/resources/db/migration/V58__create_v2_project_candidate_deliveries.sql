CREATE TABLE agent_v2_project_candidate_deliveries (
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    objective_text VARCHAR(2000) NOT NULL,
    paths_json LONGTEXT NOT NULL,
    project_version_id VARCHAR(128) NOT NULL,
    user_message_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    lease_owner_id VARCHAR(128) NOT NULL,
    lease_token VARCHAR(128) NOT NULL,
    lease_expires_at TIMESTAMP(6) NOT NULL,
    plan_id VARCHAR(128) NULL,
    workspace_id VARCHAR(128) NULL,
    artifact_id BIGINT NULL,
    candidate_fingerprint VARCHAR(64) NULL,
    diff_fingerprint VARCHAR(64) NULL,
    assistant_message_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id, project_id, session_id, client_request_id),
    CONSTRAINT uk_v2_project_candidate_turn UNIQUE (turn_id),
    CONSTRAINT uk_v2_project_candidate_plan UNIQUE (plan_id),
    CONSTRAINT uk_v2_project_candidate_artifact UNIQUE (artifact_id),
    CONSTRAINT uk_v2_project_candidate_message UNIQUE (assistant_message_id)
);

CREATE TABLE agent_v2_project_candidate_steps (
    plan_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    effect_kind VARCHAR(64) NOT NULL,
    authority_json LONGTEXT NOT NULL,
    authority_sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (plan_id, step_id),
    CONSTRAINT fk_v2_project_candidate_step_delivery FOREIGN KEY (plan_id)
        REFERENCES agent_v2_project_candidate_deliveries(plan_id)
);
