CREATE TABLE agent_v2_chain_commands (
    command_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    command_kind VARCHAR(32) NOT NULL,
    target_task_id VARCHAR(128) NULL,
    target_client_request_id VARCHAR(128) NULL,
    gap_id VARCHAR(128) NULL,
    request_sha256 CHAR(64) NOT NULL,
    turn_id BIGINT NULL,
    user_message_id BIGINT NULL,
    result_task_id VARCHAR(128) NULL,
    result_event_id VARCHAR(128) NULL,
    result_instruction_id VARCHAR(128) NULL,
    status VARCHAR(16) NOT NULL,
    result_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    committed_at DATETIME(6) NULL,
    PRIMARY KEY (command_id),
    CONSTRAINT uk_chain_command_request
        UNIQUE (user_id, session_id, client_request_id),
    CONSTRAINT ck_chain_command_kind CHECK (command_kind IN (
        'INITIAL','SUPPLEMENT','CORRECTION','REPLACEMENT',
        'CANCEL','ANSWER_TO_PENDING_ITEM')),
    CONSTRAINT ck_chain_command_status
        CHECK (status IN ('RECEIVED','COMMITTED','FAILED')),
    CONSTRAINT ck_chain_command_request_hash
        CHECK (request_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_command_result_refs CHECK (
        (result_task_id IS NULL AND result_event_id IS NULL
            AND result_instruction_id IS NULL)
        OR
        (result_task_id IS NOT NULL AND result_event_id IS NOT NULL
            AND result_instruction_id IS NOT NULL)),
    CONSTRAINT ck_chain_command_terminal CHECK (
        (status = 'RECEIVED' AND committed_at IS NULL AND result_code IS NULL)
        OR
        (status = 'COMMITTED' AND committed_at IS NOT NULL
            AND result_task_id IS NOT NULL AND result_code IS NULL)
        OR
        (status = 'FAILED' AND committed_at IS NOT NULL
            AND result_code IS NOT NULL)),
    CONSTRAINT ck_chain_command_message CHECK (
        status <> 'COMMITTED' OR command_kind = 'CANCEL'
        OR (turn_id IS NOT NULL AND user_message_id IS NOT NULL))
);

CREATE TABLE agent_v2_chain_tasks (
    task_id VARCHAR(128) NOT NULL,
    created_by_command_id VARCHAR(128) NOT NULL,
    source_instruction_id VARCHAR(128) NOT NULL,
    predecessor_task_id VARCHAR(128) NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    request_message_id BIGINT NULL,
    root_client_request_id VARCHAR(128) NOT NULL,
    root_request_sha256 CHAR(64) NOT NULL,
    project_id BIGINT NULL,
    initial_project_version VARCHAR(255) NULL,
    next_event_sequence BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT uk_chain_task_command UNIQUE (created_by_command_id),
    CONSTRAINT uk_chain_task_root
        UNIQUE (user_id, session_id, root_client_request_id),
    CONSTRAINT uk_chain_task_turn UNIQUE (turn_id),
    CONSTRAINT fk_chain_task_command FOREIGN KEY (created_by_command_id)
        REFERENCES agent_v2_chain_commands(command_id),
    CONSTRAINT ck_chain_task_root_hash
        CHECK (root_request_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_task_project_pair CHECK (
        (project_id IS NULL AND initial_project_version IS NULL)
        OR
        (project_id IS NOT NULL AND initial_project_version IS NOT NULL)),
    CONSTRAINT ck_chain_task_event_sequence CHECK (next_event_sequence >= 0)
);

CREATE TABLE agent_v2_chain_authority_events (
    event_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    transition_id VARCHAR(128) NULL,
    source_identity_sha256 CHAR(64) NOT NULL,
    committed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_chain_event_task_sequence
        UNIQUE (task_id, event_sequence),
    CONSTRAINT uk_chain_event_identity UNIQUE (task_id, event_id),
    CONSTRAINT fk_chain_event_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_event_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_chain_event_source_hash
        CHECK (source_identity_sha256 REGEXP '^[0-9a-f]{64}$')
);

CREATE INDEX idx_chain_event_task_sequence
    ON agent_v2_chain_authority_events (task_id, event_sequence);
CREATE INDEX idx_chain_event_transition
    ON agent_v2_chain_authority_events (transition_id);

CREATE TABLE agent_v2_chain_instructions (
    instruction_id VARCHAR(128) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    session_id BIGINT NOT NULL,
    origin_task_id VARCHAR(128) NOT NULL,
    message_id BIGINT NULL,
    body_sha256 CHAR(64) NULL,
    message_identity_key VARCHAR(255) NOT NULL,
    relation_kind VARCHAR(32) NOT NULL,
    parent_instruction_id VARCHAR(128) NULL,
    answered_gap_id VARCHAR(128) NULL,
    effective_boundary_digest CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (instruction_id),
    CONSTRAINT uk_chain_instruction_command UNIQUE (command_id),
    CONSTRAINT uk_chain_instruction_message
        UNIQUE (session_id, message_identity_key),
    CONSTRAINT fk_chain_instruction_command FOREIGN KEY (command_id)
        REFERENCES agent_v2_chain_commands(command_id),
    CONSTRAINT ck_chain_instruction_relation CHECK (relation_kind IN (
        'INITIAL','SUPPLEMENT','CORRECTION','REPLACEMENT',
        'CANCEL','ANSWER_TO_PENDING_ITEM')),
    CONSTRAINT ck_chain_instruction_message_identity
        CHECK (CHAR_LENGTH(TRIM(message_identity_key)) > 0),
    CONSTRAINT ck_chain_instruction_body CHECK (
        (relation_kind = 'CANCEL' AND message_id IS NULL
            AND body_sha256 IS NULL)
        OR
        (relation_kind <> 'CANCEL' AND message_id IS NOT NULL
            AND body_sha256 IS NOT NULL
            AND body_sha256 REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT ck_chain_instruction_boundary_hash
        CHECK (effective_boundary_digest REGEXP '^[0-9a-f]{64}$')
);

CREATE TABLE agent_v2_chain_task_instruction_bindings (
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    task_instruction_sequence BIGINT NOT NULL,
    relation_role VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id, task_instruction_sequence),
    CONSTRAINT uk_chain_task_instruction
        UNIQUE (task_id, instruction_id),
    CONSTRAINT uk_chain_task_instruction_event UNIQUE (event_id),
    CONSTRAINT fk_chain_task_instruction_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_task_instruction_event
        FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_task_instruction_instruction
        FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_task_instruction_sequence
        CHECK (task_instruction_sequence > 0),
    CONSTRAINT ck_chain_task_instruction_role
        CHECK (relation_role IN ('ORIGIN','INHERITED_ROOT'))
);

CREATE TABLE agent_v2_plan_replans (
    replan_event_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    source_event_sequence BIGINT NOT NULL,
    source_revision_id VARCHAR(128) NOT NULL,
    source_revision_number BIGINT NOT NULL,
    result_revision_id VARCHAR(128) NOT NULL,
    result_revision_number BIGINT NOT NULL,
    source_checkpoint_version BIGINT NOT NULL,
    result_checkpoint_version BIGINT NOT NULL,
    result_event_sequence BIGINT NOT NULL,
    lease_owner VARCHAR(255) NOT NULL,
    fence_token BIGINT NOT NULL,
    request_format_version INT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    committed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (replan_event_id),
    CONSTRAINT uk_plan_replan_source
        UNIQUE (plan_id, source_event_sequence),
    CONSTRAINT fk_plan_replan_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_replan_bootstrap FOREIGN KEY (plan_id)
        REFERENCES agent_v2_plan_bootstraps(plan_id),
    CONSTRAINT ck_plan_replan_sequences CHECK (
        source_event_sequence > 0
        AND result_event_sequence > source_event_sequence),
    CONSTRAINT ck_plan_replan_revisions CHECK (
        source_revision_number > 0
        AND result_revision_number = source_revision_number + 1),
    CONSTRAINT ck_plan_replan_checkpoints CHECK (
        source_checkpoint_version > 0
        AND result_checkpoint_version = source_checkpoint_version + 1),
    CONSTRAINT ck_plan_replan_fence CHECK (
        CHAR_LENGTH(TRIM(lease_owner)) > 0 AND fence_token > 0),
    CONSTRAINT ck_plan_replan_formats CHECK (
        request_format_version = 1 AND result_format_version = 1),
    CONSTRAINT ck_plan_replan_hashes CHECK (
        request_sha256 REGEXP '^[0-9a-f]{64}$'
        AND result_sha256 REGEXP '^[0-9a-f]{64}$')
);

CREATE INDEX idx_plan_replan_source
    ON agent_v2_plan_replans (plan_id, source_event_sequence);
