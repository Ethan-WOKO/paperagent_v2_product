CREATE TABLE agent_v2_adaptive_turns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    intake_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    route VARCHAR(32) NOT NULL,
    plan_id VARCHAR(128) NULL,
    project_version VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    steps_json LONGTEXT NOT NULL,
    final_text LONGTEXT NULL,
    candidate_artifact_id BIGINT NULL,
    output_paths_json LONGTEXT NOT NULL,
    error_code VARCHAR(64) NULL,
    reflection_count INT NOT NULL,
    replan_count INT NOT NULL,
    repair_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_v2_adaptive_intake (intake_id),
    UNIQUE KEY uk_agent_v2_adaptive_request
        (user_id, session_id, client_request_id),
    CONSTRAINT fk_agent_v2_adaptive_intake FOREIGN KEY (intake_id)
        REFERENCES agent_v2_turn_intakes(id),
    CONSTRAINT ck_agent_v2_adaptive_status CHECK
        (status IN ('PLANNING','RUNNING','WAITING_CONFIRMATION','SUCCEEDED','FAILED'))
);

CREATE TABLE agent_v2_natural_candidate_authorities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    project_version VARCHAR(255) NOT NULL,
    objective VARCHAR(2000) NOT NULL,
    paths_json LONGTEXT NOT NULL,
    authority_json LONGTEXT NOT NULL,
    authority_sha256 VARCHAR(64) NOT NULL,
    replacements_json LONGTEXT NULL,
    diff_fingerprint VARCHAR(64) NULL,
    candidate_artifact_id BIGINT NULL,
    candidate_fingerprint VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_v2_natural_candidate_plan (plan_id),
    UNIQUE KEY uk_agent_v2_natural_candidate_step (plan_id, step_id),
    CONSTRAINT ck_agent_v2_natural_candidate_status CHECK
        (status IN ('BOUND','PREPARED','PUBLISHED'))
);
