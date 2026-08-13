CREATE TABLE agent_v2_chain_context_revisions (
    context_revision_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    parent_context_revision_id VARCHAR(128) NULL,
    role VARCHAR(32) NOT NULL,
    work_state VARCHAR(64) NOT NULL,
    call_reason VARCHAR(64) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NULL,
    plan_id VARCHAR(128) NULL,
    plan_revision_id VARCHAR(128) NULL,
    plan_revision_number BIGINT NULL,
    step_id VARCHAR(128) NULL,
    activation_event_id VARCHAR(128) NULL,
    project_id BIGINT NULL,
    project_version VARCHAR(255) NULL,
    workspace_id VARCHAR(128) NULL,
    candidate_artifact_id BIGINT NULL,
    candidate_fingerprint CHAR(64) NULL,
    validation_id VARCHAR(128) NULL,
    validation_request_digest CHAR(64) NULL,
    validation_receipt_digest CHAR(64) NULL,
    projector_set_version VARCHAR(64) NOT NULL,
    pagination_version VARCHAR(64) NOT NULL,
    runtime_policy_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    module_count INT NOT NULL,
    request_manifest_format_version INT NULL,
    request_manifest_json LONGTEXT NULL,
    request_digest CHAR(64) NULL,
    completion_token VARCHAR(128) NULL,
    blocked_error_code VARCHAR(64) NULL,
    input_digest CHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (context_revision_id),
    CONSTRAINT uk_chain_context_completion
        UNIQUE (task_id, context_revision_id, completion_token),
    CONSTRAINT uk_chain_context_task_identity
        UNIQUE (task_id, context_revision_id),
    CONSTRAINT fk_chain_context_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_context_parent
        FOREIGN KEY (task_id, parent_context_revision_id)
        REFERENCES agent_v2_chain_context_revisions(task_id, context_revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_context_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_context_role
        CHECK (role IN ('PLANNER','EXECUTOR','REFLECTOR','ANSWER')),
    CONSTRAINT ck_chain_context_work_state CHECK (work_state IN (
        'PLANNING','CLASSIFYING_INSTRUCTION','DIRECT_ANSWERING','EXECUTING',
        'AWAITING_REVIEW','VALIDATING_PENDING_ITEM','WAITING_USER',
        'WAITING_PERMISSION','FINALIZING','DELIVERING','TERMINAL')),
    CONSTRAINT ck_chain_context_status
        CHECK (status IN ('BUILDING','COMPLETE','INPUT_BLOCKED')),
    CONSTRAINT ck_chain_context_module_count
        CHECK (module_count BETWEEN 0 AND 13),
    CONSTRAINT ck_chain_context_plan_identity CHECK (
        (plan_id IS NULL AND plan_revision_id IS NULL
            AND plan_revision_number IS NULL)
        OR
        (plan_id IS NOT NULL AND plan_revision_id IS NOT NULL
            AND plan_revision_number IS NOT NULL
            AND plan_revision_number > 0)),
    CONSTRAINT ck_chain_context_step_identity CHECK (
        (step_id IS NULL AND activation_event_id IS NULL)
        OR
        (step_id IS NOT NULL AND activation_event_id IS NOT NULL)),
    CONSTRAINT ck_chain_context_project_identity CHECK (
        (project_id IS NULL AND project_version IS NULL)
        OR
        (project_id IS NOT NULL AND project_version IS NOT NULL)),
    CONSTRAINT ck_chain_context_candidate_identity CHECK (
        (candidate_artifact_id IS NULL AND candidate_fingerprint IS NULL)
        OR
        (candidate_artifact_id IS NOT NULL
            AND candidate_fingerprint IS NOT NULL
            AND candidate_fingerprint REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT ck_chain_context_validation_identity CHECK (
        (validation_id IS NULL AND validation_request_digest IS NULL
            AND validation_receipt_digest IS NULL)
        OR
        (validation_id IS NOT NULL
            AND validation_request_digest IS NOT NULL
            AND validation_receipt_digest IS NOT NULL
            AND validation_request_digest REGEXP '^[0-9a-f]{64}$'
            AND validation_receipt_digest REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT ck_chain_context_terminal_cut CHECK (
        (status = 'BUILDING' AND module_count BETWEEN 0 AND 12
            AND request_manifest_format_version IS NULL
            AND request_manifest_json IS NULL AND request_digest IS NULL
            AND completion_token IS NULL AND blocked_error_code IS NULL
            AND input_digest IS NULL AND completed_at IS NULL)
        OR
        (status = 'COMPLETE' AND module_count = 13
            AND request_manifest_format_version IS NOT NULL
            AND request_manifest_format_version = 1
            AND request_manifest_json IS NOT NULL
            AND request_digest IS NOT NULL
            AND request_digest REGEXP '^[0-9a-f]{64}$'
            AND completion_token IS NOT NULL
            AND blocked_error_code IS NULL AND input_digest IS NULL
            AND completed_at IS NOT NULL)
        OR
        (status = 'INPUT_BLOCKED' AND module_count = 13
            AND request_manifest_format_version IS NOT NULL
            AND request_manifest_format_version = 1
            AND request_manifest_json IS NOT NULL AND request_digest IS NULL
            AND completion_token IS NULL AND blocked_error_code IS NOT NULL
            AND input_digest IS NOT NULL
            AND input_digest REGEXP '^[0-9a-f]{64}$'
            AND completed_at IS NOT NULL))
);

CREATE INDEX idx_chain_context_task_created
    ON agent_v2_chain_context_revisions (task_id, created_at);

CREATE TABLE agent_v2_chain_context_modules (
    context_revision_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    module_ordinal INT NOT NULL,
    module_kind VARCHAR(64) NOT NULL,
    presence_kind VARCHAR(16) NOT NULL,
    source_version_format_version INT NOT NULL,
    source_version_sha256 CHAR(64) NOT NULL,
    source_version_json LONGTEXT NOT NULL,
    read_boundary_format_version INT NOT NULL,
    read_boundary_sha256 CHAR(64) NOT NULL,
    read_boundary_json LONGTEXT NOT NULL,
    projection_version VARCHAR(64) NOT NULL,
    pagination_version VARCHAR(64) NOT NULL,
    projection_parameters_format_version INT NOT NULL,
    projection_parameters_sha256 CHAR(64) NOT NULL,
    projection_parameters_json LONGTEXT NOT NULL,
    projection_format_version INT NOT NULL,
    projection_digest CHAR(64) NOT NULL,
    projection_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (context_revision_id, module_ordinal),
    CONSTRAINT uk_chain_context_module_kind
        UNIQUE (context_revision_id, module_kind),
    CONSTRAINT fk_chain_context_module_context
        FOREIGN KEY (task_id, context_revision_id)
        REFERENCES agent_v2_chain_context_revisions(task_id, context_revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_context_module_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_context_module_ordinal
        CHECK (module_ordinal BETWEEN 1 AND 13),
    CONSTRAINT ck_chain_context_module_kind CHECK (module_kind IN (
        'INSTRUCTION_CHAIN','CONVERSATION','PROJECT_INPUTS','TASK_CONTRACT',
        'PLAN_CONTRACT','EXECUTION_STATE','ACTION_AND_ERRORS',
        'WORKSPACE_CANDIDATE','VALIDATION_PUBLISH','REVIEW_PENDING',
        'MEMORY_EVIDENCE','RUNTIME_CAPABILITY_PERMISSION','MODEL_HISTORY')),
    CONSTRAINT ck_chain_context_module_presence
        CHECK (presence_kind IN ('PRESENT','EMPTY')),
    CONSTRAINT ck_chain_context_module_formats CHECK (
        source_version_format_version = 1
        AND read_boundary_format_version = 1
        AND projection_parameters_format_version = 1
        AND projection_format_version = 1),
    CONSTRAINT ck_chain_context_module_hashes CHECK (
        source_version_sha256 REGEXP '^[0-9a-f]{64}$'
        AND read_boundary_sha256 REGEXP '^[0-9a-f]{64}$'
        AND projection_parameters_sha256 REGEXP '^[0-9a-f]{64}$'
        AND projection_digest REGEXP '^[0-9a-f]{64}$')
);

CREATE TABLE agent_v2_chain_model_invocations (
    invocation_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    context_revision_id VARCHAR(128) NOT NULL,
    completion_token VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    work_state VARCHAR(64) NOT NULL,
    call_reason VARCHAR(64) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    model VARCHAR(128) NOT NULL,
    invocation_ordinal INT NOT NULL,
    runtime_policy_version VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (invocation_id),
    CONSTRAINT uk_chain_invocation_context_ordinal
        UNIQUE (context_revision_id, invocation_ordinal),
    CONSTRAINT uk_chain_invocation_task_identity UNIQUE (task_id, invocation_id),
    CONSTRAINT fk_chain_invocation_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_invocation_complete_context
        FOREIGN KEY (task_id, context_revision_id, completion_token)
        REFERENCES agent_v2_chain_context_revisions(
            task_id, context_revision_id, completion_token)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_invocation_role
        CHECK (role IN ('PLANNER','EXECUTOR','REFLECTOR','ANSWER')),
    CONSTRAINT ck_chain_invocation_work_state CHECK (work_state IN (
        'PLANNING','CLASSIFYING_INSTRUCTION','DIRECT_ANSWERING','EXECUTING',
        'AWAITING_REVIEW','VALIDATING_PENDING_ITEM','WAITING_USER',
        'WAITING_PERMISSION','FINALIZING','DELIVERING','TERMINAL')),
    CONSTRAINT ck_chain_invocation_ordinal CHECK (invocation_ordinal > 0)
);

CREATE INDEX idx_chain_invocation_task_created
    ON agent_v2_chain_model_invocations (task_id, created_at);

CREATE TABLE agent_v2_chain_provider_attempts (
    invocation_id VARCHAR(128) NOT NULL,
    attempt_no INT NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    duration_ms BIGINT NOT NULL,
    finish_reason VARCHAR(64) NULL,
    schema_validation_status VARCHAR(16) NOT NULL,
    proposal_validation_status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (invocation_id, attempt_no),
    CONSTRAINT fk_chain_attempt_invocation
        FOREIGN KEY (task_id, invocation_id)
        REFERENCES agent_v2_chain_model_invocations(task_id, invocation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_attempt_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_attempt_number CHECK (attempt_no BETWEEN 1 AND 3),
    CONSTRAINT ck_chain_attempt_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_chain_attempt_schema_status
        CHECK (schema_validation_status IN ('NOT_RUN','PASSED','FAILED')),
    CONSTRAINT ck_chain_attempt_proposal_status
        CHECK (proposal_validation_status IN ('NOT_RUN','PASSED','FAILED'))
);

CREATE TABLE agent_v2_chain_contents (
    content_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    content_kind VARCHAR(32) NOT NULL,
    body LONGTEXT NOT NULL,
    body_sha256 CHAR(64) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (content_id),
    CONSTRAINT uk_chain_content_invocation_kind
        UNIQUE (invocation_id, content_kind),
    CONSTRAINT uk_chain_content_task_identity UNIQUE (task_id, content_id),
    CONSTRAINT fk_chain_content_invocation
        FOREIGN KEY (task_id, invocation_id)
        REFERENCES agent_v2_chain_model_invocations(task_id, invocation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_content_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_content_kind CHECK (content_kind IN (
        'ANSWER_BODY','CANDIDATE_STEP_RESULT','WORKSPACE_CHANGE_BODY')),
    CONSTRAINT ck_chain_content_hash
        CHECK (body_sha256 REGEXP '^[0-9a-f]{64}$')
);

CREATE TABLE agent_v2_chain_model_proposals (
    proposal_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    schema_version INT NOT NULL,
    role VARCHAR(32) NOT NULL,
    proposal_kind VARCHAR(64) NOT NULL,
    payload_format_version INT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    source_refs_format_version INT NOT NULL,
    source_refs_sha256 CHAR(64) NOT NULL,
    source_refs_json LONGTEXT NOT NULL,
    body_authority_type VARCHAR(32) NULL,
    body_authority_ref VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (proposal_id),
    CONSTRAINT uk_chain_proposal_invocation UNIQUE (invocation_id),
    CONSTRAINT uk_chain_proposal_task_identity UNIQUE (task_id, proposal_id),
    CONSTRAINT fk_chain_proposal_invocation
        FOREIGN KEY (task_id, invocation_id)
        REFERENCES agent_v2_chain_model_invocations(task_id, invocation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_proposal_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_proposal_schema CHECK (schema_version = 1),
    CONSTRAINT ck_chain_proposal_role
        CHECK (role IN ('PLANNER','EXECUTOR','REFLECTOR','ANSWER')),
    CONSTRAINT ck_chain_proposal_kind CHECK (proposal_kind IN (
        'DIRECT_ROUTE','PERSISTENT_PLAN','PLAN_REVISION','NEED_USER_INPUT',
        'NEED_PERMISSION','USER_INSTRUCTION_DISPOSITION','PLANNING_BLOCKED',
        'TOOL_ACTION','WORKSPACE_CHANGE','STEP_RESULT','STEP_BLOCKED',
        'CONTINUE_STEP','ACCEPT_STEP','ACCEPT_STEP_AND_READY_TO_FINALIZE',
        'REPLAN_REQUIRED','READY_TO_FINALIZE','TASK_FAILED','DIRECT_ANSWER',
        'ESCALATE_TO_PERSISTENT','USER_QUESTION','STATUS_OR_FAILURE',
        'FINAL_DELIVERY','DELIVERY_BLOCKED')),
    CONSTRAINT ck_chain_proposal_formats CHECK (
        payload_format_version = 1 AND source_refs_format_version = 1),
    CONSTRAINT ck_chain_proposal_hashes CHECK (
        payload_sha256 REGEXP '^[0-9a-f]{64}$'
        AND source_refs_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_proposal_body_ref CHECK (
        (body_authority_type IS NULL AND body_authority_ref IS NULL)
        OR
        (body_authority_type IS NOT NULL AND body_authority_ref IS NOT NULL))
);

CREATE TABLE agent_v2_chain_proposal_state_events (
    proposal_id VARCHAR(128) NOT NULL,
    state_sequence BIGINT NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    state_kind VARCHAR(48) NOT NULL,
    official_authority_type VARCHAR(64) NULL,
    official_authority_ref VARCHAR(128) NULL,
    committed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (proposal_id, state_sequence),
    CONSTRAINT uk_chain_proposal_state_event UNIQUE (event_id),
    CONSTRAINT fk_chain_proposal_state_proposal
        FOREIGN KEY (task_id, proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_proposal_state_event
        FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_proposal_state_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_proposal_state_sequence CHECK (state_sequence > 0),
    CONSTRAINT ck_chain_proposal_state_kind CHECK (state_kind IN (
        'ACCEPTED','REJECTED','STALE','REPLACED_BY_OFFICIAL_RESULT')),
    CONSTRAINT ck_chain_proposal_state_authority CHECK (
        (state_kind = 'REPLACED_BY_OFFICIAL_RESULT'
            AND official_authority_type IS NOT NULL
            AND official_authority_ref IS NOT NULL)
        OR
        (state_kind IN ('ACCEPTED','REJECTED','STALE')
            AND official_authority_type IS NULL
            AND official_authority_ref IS NULL))
);
