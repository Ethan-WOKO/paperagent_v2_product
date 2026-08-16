CREATE TABLE reactplan_turn_intakes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    turn_id BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL,
    task_id VARCHAR(69) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reactplan_turn_intake_request (user_id, session_id, client_request_id),
    UNIQUE KEY uk_reactplan_turn_intake_turn (turn_id),
    UNIQUE KEY uk_reactplan_turn_intake_task (task_id)
);
