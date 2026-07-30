CREATE TABLE agent_v2_turn_intakes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    content_text LONGTEXT NOT NULL,
    rag_disabled BOOLEAN NOT NULL,
    skill_id VARCHAR(128) NULL,
    experiment_json LONGTEXT NULL,
    user_message_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    assistant_message_id BIGINT NULL,
    plan_id VARCHAR(128) NULL,
    planner_output_json LONGTEXT NULL,
    capability_bindings_json LONGTEXT NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_v2_turn_intake_request
        (user_id, session_id, client_request_id),
    UNIQUE KEY uk_agent_v2_turn_intake_turn (turn_id),
    UNIQUE KEY uk_agent_v2_turn_intake_plan (plan_id),
    CONSTRAINT fk_agent_v2_turn_intake_session FOREIGN KEY (session_id)
        REFERENCES agent_sessions(id),
    CONSTRAINT fk_agent_v2_turn_intake_user_message FOREIGN KEY (user_message_id)
        REFERENCES agent_messages(id),
    CONSTRAINT fk_agent_v2_turn_intake_turn FOREIGN KEY (turn_id)
        REFERENCES agent_turns(id),
    CONSTRAINT fk_agent_v2_turn_intake_assistant_message FOREIGN KEY (assistant_message_id)
        REFERENCES agent_messages(id),
    CONSTRAINT ck_agent_v2_turn_intake_status CHECK
        (status IN ('RUNNING', 'DIRECT', 'PERSISTENT', 'FAILED'))
);
