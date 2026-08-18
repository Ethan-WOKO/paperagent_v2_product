CREATE TABLE reactplan_model_completions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(69) NOT NULL,
    client_request_id VARCHAR(125) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    response_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reactplan_model_request UNIQUE (task_id, client_request_id),
    CONSTRAINT chk_reactplan_model_state CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);
