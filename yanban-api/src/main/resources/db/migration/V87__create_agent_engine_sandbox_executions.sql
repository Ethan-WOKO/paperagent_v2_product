CREATE TABLE agent_engine_sandbox_executions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(69) NOT NULL,
    client_request_id VARCHAR(125) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    semantic_digest CHAR(64) NOT NULL,
    execution_ref VARCHAR(80) NOT NULL,
    broker_execution_ref VARCHAR(256) NULL,
    state VARCHAR(32) NOT NULL,
    request_json LONGTEXT NOT NULL,
    receipt_ref VARCHAR(80) NULL,
    receipt_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_engine_sandbox_request (task_id, client_request_id),
    UNIQUE KEY uk_engine_sandbox_execution_ref (execution_ref),
    UNIQUE KEY uk_engine_sandbox_receipt_ref (receipt_ref),
    CONSTRAINT ck_engine_sandbox_state CHECK (
        state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED','SYSTEM_ERROR'))
);
