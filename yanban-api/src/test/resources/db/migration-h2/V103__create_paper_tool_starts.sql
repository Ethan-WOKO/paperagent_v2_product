CREATE TABLE paper_tool_starts (
    user_id BIGINT NOT NULL,
    request_key CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    dispatch_state VARCHAR(16) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_key),
    CONSTRAINT fk_paper_tool_start_user FOREIGN KEY (user_id) REFERENCES sys_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_paper_tool_start_task FOREIGN KEY (task_id) REFERENCES paper_tasks(id) ON DELETE CASCADE,
    CONSTRAINT uq_paper_tool_start_task UNIQUE (task_id)
);
