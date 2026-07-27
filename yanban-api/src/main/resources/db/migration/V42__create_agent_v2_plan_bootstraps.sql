CREATE TABLE agent_v2_plan_bootstraps (
    plan_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    payload_format_version INT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (plan_id),
    CONSTRAINT uk_agent_v2_plan_bootstraps_task_frame UNIQUE (task_frame_id),
    CONSTRAINT ck_agent_v2_plan_bootstraps_sha256
        CHECK (payload_sha256 REGEXP '^[0-9a-f]{64}$')
);
