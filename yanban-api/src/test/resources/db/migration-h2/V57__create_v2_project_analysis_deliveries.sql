ALTER TABLE agent_v2_final_syntheses
    ADD COLUMN source_project_id VARCHAR(128);
ALTER TABLE agent_v2_final_syntheses
    ADD COLUMN source_project_version_id VARCHAR(128);
ALTER TABLE agent_v2_final_syntheses
    ADD COLUMN workspace_diff_json CLOB;

CREATE TABLE agent_v2_project_analysis_deliveries (
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    objective_text VARCHAR(2000) NOT NULL,
    paths_json CLOB NOT NULL,
    search_query VARCHAR(256),
    max_search_results INT NOT NULL,
    project_version_id VARCHAR(128) NOT NULL,
    user_message_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL UNIQUE,
    lease_owner_id VARCHAR(128) NOT NULL,
    lease_token VARCHAR(128) NOT NULL,
    lease_expires_at TIMESTAMP(6) NOT NULL,
    plan_id VARCHAR(128) UNIQUE,
    workspace_id VARCHAR(128),
    synthesis_id VARCHAR(128),
    assistant_message_id BIGINT UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id, project_id, session_id, client_request_id),
    CONSTRAINT fk_v2_project_analysis_synthesis FOREIGN KEY (synthesis_id)
        REFERENCES agent_v2_final_syntheses(synthesis_id)
);

CREATE TABLE agent_v2_project_analysis_steps (
    plan_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    effect_kind VARCHAR(64) NOT NULL,
    argument_json CLOB NOT NULL,
    argument_sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (plan_id, step_id),
    CONSTRAINT fk_v2_project_analysis_step_delivery FOREIGN KEY (plan_id)
        REFERENCES agent_v2_project_analysis_deliveries(plan_id)
);
