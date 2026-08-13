CREATE TABLE agent_v2_chain_task_skill_snapshots (
    task_id VARCHAR(128) NOT NULL,
    source_instruction_id VARCHAR(128) NOT NULL,
    selection_kind VARCHAR(16) NOT NULL,
    skill_id VARCHAR(128) NULL,
    prompt_sha256 CHAR(64) NULL,
    prompt_body LONGTEXT NULL,
    allowed_tools_format_version INT NOT NULL,
    allowed_tools_sha256 CHAR(64) NOT NULL,
    allowed_tools_json LONGTEXT NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT fk_chain_skill_snapshot_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_skill_snapshot_source
        FOREIGN KEY (task_id, source_instruction_id)
        REFERENCES agent_v2_chain_task_instruction_bindings(
            task_id, instruction_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_skill_snapshot_kind
        CHECK (selection_kind IN ('NONE','SELECTED')),
    CONSTRAINT ck_chain_skill_snapshot_selection CHECK (
        (selection_kind = 'NONE' AND skill_id IS NULL
            AND prompt_sha256 IS NULL AND prompt_body IS NULL)
        OR
        (selection_kind = 'SELECTED'
            AND CHAR_LENGTH(TRIM(skill_id)) > 0
            AND prompt_sha256 REGEXP '^[0-9a-f]{64}$'
            AND prompt_body IS NOT NULL)),
    CONSTRAINT ck_chain_skill_snapshot_format
        CHECK (allowed_tools_format_version = 1),
    CONSTRAINT ck_chain_skill_snapshot_hashes CHECK (
        allowed_tools_sha256 REGEXP '^[0-9a-f]{64}$'
        AND snapshot_sha256 REGEXP '^[0-9a-f]{64}$')
);
