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
    candidate_artifact_id VARCHAR(128) NULL,
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
