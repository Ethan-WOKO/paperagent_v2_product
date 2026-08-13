CREATE TABLE agent_v2_chain_context_build_failures (
    context_build_failure_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    context_revision_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    work_state VARCHAR(64) NOT NULL,
    call_reason VARCHAR(64) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    failed_module VARCHAR(64) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    projector_set_version VARCHAR(64) NOT NULL,
    pagination_version VARCHAR(64) NOT NULL,
    runtime_policy_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (context_build_failure_id),
    CONSTRAINT uk_chain_context_build_failure_context
        UNIQUE (context_revision_id),
    CONSTRAINT uk_chain_context_build_failure_event UNIQUE (event_id),
    CONSTRAINT fk_chain_context_build_failure_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_context_build_failure_context
        FOREIGN KEY (task_id, context_revision_id)
        REFERENCES agent_v2_chain_context_revisions(task_id, context_revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_context_build_failure_event FOREIGN KEY (event_id)
        REFERENCES agent_v2_chain_authority_events(event_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_context_build_failure_instruction
        FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_context_build_failure_role
        CHECK (role IN ('PLANNER','EXECUTOR','REFLECTOR','ANSWER')),
    CONSTRAINT ck_chain_context_build_failure_work_state CHECK (work_state IN (
        'PLANNING','CLASSIFYING_INSTRUCTION','DIRECT_ANSWERING','EXECUTING',
        'AWAITING_REVIEW','VALIDATING_PENDING_ITEM','WAITING_USER',
        'WAITING_PERMISSION','FINALIZING','DELIVERING','TERMINAL')),
    CONSTRAINT ck_chain_context_build_failure_module CHECK (failed_module IN (
        'INSTRUCTION_CHAIN','CONVERSATION','PROJECT_INPUTS','TASK_CONTRACT',
        'PLAN_CONTRACT','EXECUTION_STATE','ACTION_AND_ERRORS',
        'WORKSPACE_CANDIDATE','VALIDATION_PUBLISH','REVIEW_PENDING',
        'MEMORY_EVIDENCE','RUNTIME_CAPABILITY_PERMISSION','MODEL_HISTORY')),
    CONSTRAINT ck_chain_context_build_failure_code
        CHECK (error_code = 'CONTEXT_INPUT_BLOCKED')
);
