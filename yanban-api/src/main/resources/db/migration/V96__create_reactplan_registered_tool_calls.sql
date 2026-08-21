CREATE TABLE reactplan_registered_tool_calls (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(69) NOT NULL,
    call_id VARCHAR(45) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    response_json LONGTEXT NULL,
    error_code VARCHAR(96) NULL,
    replay_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_reactplan_registered_tool_call UNIQUE (task_id, call_id),
    CONSTRAINT chk_reactplan_registered_tool_state CHECK (state IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_reactplan_registered_tool_task FOREIGN KEY (task_id)
        REFERENCES reactplan_task_checkpoints(task_id) ON DELETE CASCADE
);

CREATE INDEX idx_reactplan_registered_tool_task_created
    ON reactplan_registered_tool_calls(task_id, created_at);
