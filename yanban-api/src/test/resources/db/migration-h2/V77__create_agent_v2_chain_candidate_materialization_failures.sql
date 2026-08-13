CREATE TABLE agent_v2_chain_candidate_materialization_failures (
    candidate_failure_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    base_candidate_key VARCHAR(128) NOT NULL,
    mutation_authority_type VARCHAR(64) NOT NULL,
    mutation_authority_ref VARCHAR(128) NOT NULL,
    version_fence_sha256 CHAR(64) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (candidate_failure_id),
    CONSTRAINT uk_chain_candidate_failure_task_action UNIQUE (task_id, action_id),
    CONSTRAINT uk_chain_candidate_failure_event UNIQUE (event_id),
    CONSTRAINT fk_chain_candidate_failure_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_candidate_failure_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_candidate_failure_action FOREIGN KEY (task_id, action_id)
        REFERENCES agent_v2_chain_action_bindings(task_id, action_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_candidate_failure_authority CHECK (
        mutation_authority_type IN ('WORKSPACE_CHANGE_BODY', 'TOOL_EFFECT_RESULT')),
    CONSTRAINT ck_chain_candidate_failure_code CHECK (error_code IN (
        'CANDIDATE_REPLACEMENT_BUNDLE_INVALID',
        'CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS',
        'CANDIDATE_REPLACEMENT_TOO_LARGE',
        'CANDIDATE_NO_ACTUAL_CHANGE'))
);
