CREATE TABLE agent_v2_chain_finalization_readiness (
    readiness_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    transition_id VARCHAR(128) NOT NULL,
    readiness_scope_key CHAR(64) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    final_plan_id VARCHAR(128) NOT NULL,
    final_plan_revision_id VARCHAR(128) NOT NULL,
    final_plan_revision_number BIGINT NOT NULL,
    final_step_id VARCHAR(128) NOT NULL,
    review_decision_id VARCHAR(128) NOT NULL,
    accepted_set_format_version INT NOT NULL,
    accepted_set_sha256 CHAR(64) NOT NULL,
    accepted_set_json LONGTEXT NOT NULL,
    applicability_cut_event_sequence BIGINT NOT NULL,
    artifact_id BIGINT NULL,
    candidate_key VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    validation_id VARCHAR(128) NOT NULL,
    validation_request_digest CHAR(64) NULL,
    validation_receipt_digest CHAR(64) NULL,
    coverage_format_version INT NOT NULL,
    coverage_sha256 CHAR(64) NOT NULL,
    coverage_json LONGTEXT NOT NULL,
    publish_requirement VARCHAR(16) NOT NULL,
    publish_requirement_digest CHAR(64) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    project_version VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (readiness_id),
    CONSTRAINT uk_chain_readiness_transition UNIQUE (transition_id),
    CONSTRAINT uk_chain_readiness_scope UNIQUE (readiness_scope_key),
    CONSTRAINT uk_chain_readiness_event UNIQUE (event_id),
    CONSTRAINT uk_chain_readiness_task_identity UNIQUE (task_id, readiness_id),
    CONSTRAINT fk_chain_readiness_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_readiness_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_readiness_transition FOREIGN KEY (task_id, transition_id)
        REFERENCES agent_v2_chain_transitions(task_id, transition_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_readiness_review
        FOREIGN KEY (task_id, review_decision_id)
        REFERENCES agent_v2_chain_review_decisions(task_id, review_decision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_readiness_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_readiness_scope_hash
        CHECK (readiness_scope_key REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_readiness_revision
        CHECK (final_plan_revision_number > 0),
    CONSTRAINT ck_chain_readiness_cut
        CHECK (applicability_cut_event_sequence >= 0),
    CONSTRAINT ck_chain_readiness_formats CHECK (
        accepted_set_format_version = 1 AND coverage_format_version = 1),
    CONSTRAINT ck_chain_readiness_hashes CHECK (
        accepted_set_sha256 REGEXP '^[0-9a-f]{64}$'
        AND coverage_sha256 REGEXP '^[0-9a-f]{64}$'
        AND publish_requirement_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_readiness_publish
        CHECK (publish_requirement IN ('REQUIRED','NOT_REQUIRED')),
    CONSTRAINT ck_chain_readiness_validation CHECK (
        (validation_id = 'NONE' AND validation_request_digest IS NULL
            AND validation_receipt_digest IS NULL)
        OR
        (validation_id <> 'NONE'
            AND validation_request_digest IS NOT NULL
            AND validation_receipt_digest IS NOT NULL
            AND validation_request_digest REGEXP '^[0-9a-f]{64}$'
            AND validation_receipt_digest REGEXP '^[0-9a-f]{64}$')),
    CONSTRAINT ck_chain_readiness_candidate CHECK (
        (candidate_key = 'NONE' AND artifact_id IS NULL)
        OR
        (candidate_key <> 'NONE' AND artifact_id IS NOT NULL))
);

CREATE INDEX idx_chain_readiness_task_created
    ON agent_v2_chain_finalization_readiness (task_id, created_at);

CREATE TABLE agent_v2_chain_finalization_checks (
    finalization_check_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    readiness_id VARCHAR(128) NOT NULL,
    transition_id VARCHAR(128) NOT NULL,
    attempt_no INT NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    final_plan_revision_id VARCHAR(128) NOT NULL,
    accepted_set_sha256 CHAR(64) NOT NULL,
    candidate_key VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    validation_id VARCHAR(128) NOT NULL,
    validation_request_digest CHAR(64) NULL,
    validation_receipt_digest CHAR(64) NULL,
    publish_requirement_digest CHAR(64) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    project_version VARCHAR(255) NOT NULL,
    input_digest CHAR(64) NOT NULL,
    content_digest CHAR(64) NOT NULL,
    publish_digest CHAR(64) NOT NULL,
    result_status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    failure_disposition VARCHAR(32) NOT NULL,
    runtime_policy_version VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (finalization_check_id),
    CONSTRAINT uk_chain_finalization_check_attempt
        UNIQUE (readiness_id, attempt_no),
    CONSTRAINT uk_chain_finalization_check_event UNIQUE (event_id),
    CONSTRAINT fk_chain_finalization_check_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_finalization_check_event
        FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_finalization_check_readiness
        FOREIGN KEY (task_id, readiness_id)
        REFERENCES agent_v2_chain_finalization_readiness(task_id, readiness_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_finalization_check_transition
        FOREIGN KEY (task_id, transition_id)
        REFERENCES agent_v2_chain_transitions(task_id, transition_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_finalization_check_instruction
        FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_finalization_check_attempt
        CHECK (attempt_no BETWEEN 1 AND 2),
    CONSTRAINT ck_chain_finalization_check_status
        CHECK (result_status IN ('PASSED','FAILED')),
    CONSTRAINT ck_chain_finalization_check_disposition CHECK (
        (result_status = 'PASSED' AND error_code IS NULL
            AND failure_disposition = 'NONE')
        OR
        (result_status = 'FAILED' AND error_code IS NOT NULL
            AND failure_disposition IN ('RETRYABLE','REFLECTOR_REQUIRED'))),
    CONSTRAINT ck_chain_finalization_check_error CHECK (
        error_code IS NULL OR error_code IN (
            'READINESS_BINDING_MISMATCH','TASK_CONTRACT_UNSATISFIED',
            'ACCEPTED_RESULT_SET_MISMATCH','CANDIDATE_BINDING_MISMATCH',
            'VALIDATION_MISSING','VALIDATION_NOT_SUCCESSFUL',
            'VALIDATION_BINDING_MISMATCH','PUBLISH_REQUIREMENT_MISMATCH',
            'STALE_VERSION_FENCE','AUTHORITY_TEMPORARILY_UNAVAILABLE')),
    CONSTRAINT ck_chain_finalization_check_retry CHECK (
        failure_disposition <> 'RETRYABLE'
        OR error_code = 'AUTHORITY_TEMPORARILY_UNAVAILABLE'),
    CONSTRAINT ck_chain_finalization_check_hashes CHECK (
        accepted_set_sha256 REGEXP '^[0-9a-f]{64}$'
        AND publish_requirement_digest REGEXP '^[0-9a-f]{64}$'
        AND input_digest REGEXP '^[0-9a-f]{64}$'
        AND content_digest REGEXP '^[0-9a-f]{64}$'
        AND publish_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_finalization_check_validation CHECK (
        (validation_id = 'NONE' AND validation_request_digest IS NULL
            AND validation_receipt_digest IS NULL)
        OR
        (validation_id <> 'NONE'
            AND validation_request_digest IS NOT NULL
            AND validation_receipt_digest IS NOT NULL
            AND validation_request_digest REGEXP '^[0-9a-f]{64}$'
            AND validation_receipt_digest REGEXP '^[0-9a-f]{64}$'))
);

CREATE TABLE agent_v2_chain_task_outcomes (
    outcome_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    source_command_id VARCHAR(128) NOT NULL,
    outcome_type VARCHAR(16) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NULL,
    final_plan_id VARCHAR(128) NULL,
    final_plan_revision_id VARCHAR(128) NULL,
    coverage_format_version INT NOT NULL,
    coverage_sha256 CHAR(64) NOT NULL,
    coverage_json LONGTEXT NOT NULL,
    accepted_set_format_version INT NOT NULL,
    accepted_set_sha256 CHAR(64) NOT NULL,
    accepted_set_json LONGTEXT NOT NULL,
    final_artifact_id BIGINT NULL,
    candidate_key VARCHAR(128) NOT NULL,
    validation_id VARCHAR(128) NOT NULL,
    publish_operation_id VARCHAR(128) NULL,
    published_project_version VARCHAR(255) NULL,
    published_revision_id BIGINT NULL,
    publish_receipt_id VARCHAR(128) NULL,
    incomplete_items_format_version INT NOT NULL,
    incomplete_items_sha256 CHAR(64) NOT NULL,
    incomplete_items_json LONGTEXT NOT NULL,
    limitations_format_version INT NOT NULL,
    limitations_sha256 CHAR(64) NOT NULL,
    limitations_json LONGTEXT NOT NULL,
    risks_format_version INT NOT NULL,
    risks_sha256 CHAR(64) NOT NULL,
    risks_json LONGTEXT NOT NULL,
    failure_category VARCHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    source_decision_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (outcome_id),
    CONSTRAINT uk_chain_task_outcome_task UNIQUE (task_id),
    CONSTRAINT uk_chain_task_outcome_event UNIQUE (event_id),
    CONSTRAINT uk_chain_task_outcome_identity UNIQUE (task_id, outcome_id),
    CONSTRAINT fk_chain_task_outcome_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_task_outcome_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_task_outcome_command FOREIGN KEY (source_command_id)
        REFERENCES agent_v2_chain_commands(command_id),
    CONSTRAINT fk_chain_task_outcome_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_task_outcome_type CHECK (outcome_type IN (
        'COMPLETED','FAILED','CANCELLED','SUPERSEDED')),
    CONSTRAINT ck_chain_task_outcome_formats CHECK (
        coverage_format_version = 1 AND accepted_set_format_version = 1
        AND incomplete_items_format_version = 1
        AND limitations_format_version = 1 AND risks_format_version = 1),
    CONSTRAINT ck_chain_task_outcome_hashes CHECK (
        coverage_sha256 REGEXP '^[0-9a-f]{64}$'
        AND accepted_set_sha256 REGEXP '^[0-9a-f]{64}$'
        AND incomplete_items_sha256 REGEXP '^[0-9a-f]{64}$'
        AND limitations_sha256 REGEXP '^[0-9a-f]{64}$'
        AND risks_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_task_outcome_plan_identity CHECK (
        (final_plan_id IS NULL AND final_plan_revision_id IS NULL)
        OR
        (final_plan_id IS NOT NULL AND final_plan_revision_id IS NOT NULL)),
    CONSTRAINT ck_chain_task_outcome_candidate CHECK (
        (candidate_key = 'NONE' AND final_artifact_id IS NULL)
        OR
        (candidate_key <> 'NONE' AND final_artifact_id IS NOT NULL)),
    CONSTRAINT ck_chain_task_outcome_publish CHECK (
        (publish_operation_id IS NULL AND published_project_version IS NULL
            AND published_revision_id IS NULL AND publish_receipt_id IS NULL)
        OR
        (publish_operation_id IS NOT NULL
            AND published_project_version IS NOT NULL
            AND published_revision_id IS NOT NULL
            AND publish_receipt_id IS NOT NULL)),
    CONSTRAINT ck_chain_task_outcome_failure CHECK (
        (outcome_type = 'FAILED' AND failure_category IS NOT NULL
            AND failure_code IS NOT NULL)
        OR
        (outcome_type <> 'FAILED' AND failure_category IS NULL
            AND failure_code IS NULL))
);

CREATE TABLE agent_v2_chain_delivery_message_reservations (
    delivery_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    answer_content_id VARCHAR(128) NOT NULL,
    answer_body_sha256 CHAR(64) NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_id),
    CONSTRAINT uk_chain_delivery_reservation_message
        UNIQUE (assistant_message_id),
    CONSTRAINT uk_chain_delivery_reservation_task
        UNIQUE (task_id, delivery_id),
    CONSTRAINT fk_chain_delivery_reservation_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_reservation_content
        FOREIGN KEY (task_id, answer_content_id)
        REFERENCES agent_v2_chain_contents(task_id, content_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_delivery_reservation_hash
        CHECK (answer_body_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chain_delivery_reservation_message
        CHECK (assistant_message_id > 0)
);

CREATE TABLE agent_v2_chain_deliveries (
    delivery_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    source_command_id VARCHAR(128) NOT NULL,
    route_decision_id VARCHAR(128) NULL,
    task_outcome_id VARCHAR(128) NULL,
    gap_id VARCHAR(128) NULL,
    decision_id VARCHAR(128) NULL,
    answer_content_id VARCHAR(128) NULL,
    assistant_message_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_id),
    CONSTRAINT uk_chain_delivery_command UNIQUE (source_command_id),
    CONSTRAINT uk_chain_delivery_assistant_message UNIQUE (assistant_message_id),
    CONSTRAINT uk_chain_delivery_event UNIQUE (event_id),
    CONSTRAINT uk_chain_delivery_task_identity UNIQUE (task_id, delivery_id),
    CONSTRAINT fk_chain_delivery_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_command FOREIGN KEY (source_command_id)
        REFERENCES agent_v2_chain_commands(command_id),
    CONSTRAINT fk_chain_delivery_route FOREIGN KEY (task_id, route_decision_id)
        REFERENCES agent_v2_chain_route_decisions(task_id, route_decision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_outcome FOREIGN KEY (task_id, task_outcome_id)
        REFERENCES agent_v2_chain_task_outcomes(task_id, outcome_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_gap FOREIGN KEY (task_id, gap_id)
        REFERENCES agent_v2_chain_pending_items(task_id, gap_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_content FOREIGN KEY (task_id, answer_content_id)
        REFERENCES agent_v2_chain_contents(task_id, content_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_delivery_source CHECK (
        route_decision_id IS NOT NULL OR task_outcome_id IS NOT NULL
        OR gap_id IS NOT NULL OR decision_id IS NOT NULL),
    CONSTRAINT ck_chain_delivery_answer_binding CHECK (
        (answer_content_id IS NULL AND assistant_message_id IS NULL)
        OR
        (answer_content_id IS NOT NULL AND assistant_message_id IS NOT NULL))
);

CREATE TABLE agent_v2_chain_delivery_events (
    delivery_id VARCHAR(128) NOT NULL,
    event_sequence BIGINT NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_kind VARCHAR(32) NOT NULL,
    attempt_no INT NOT NULL,
    error_code VARCHAR(64) NULL,
    runtime_policy_version VARCHAR(64) NOT NULL,
    committed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_id, event_sequence),
    CONSTRAINT uk_chain_delivery_attempt_event
        UNIQUE (delivery_id, attempt_no, event_kind),
    CONSTRAINT uk_chain_delivery_state_event UNIQUE (event_id),
    CONSTRAINT fk_chain_delivery_state_delivery
        FOREIGN KEY (task_id, delivery_id)
        REFERENCES agent_v2_chain_deliveries(task_id, delivery_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_state_event
        FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_delivery_state_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_delivery_state_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_chain_delivery_state_kind CHECK (event_kind IN (
        'PENDING','RETRYING','SUCCEEDED','DELIVERY_FAILED')),
    CONSTRAINT ck_chain_delivery_state_attempt
        CHECK (attempt_no BETWEEN 0 AND 3),
    CONSTRAINT ck_chain_delivery_state_shape CHECK (
        (event_kind = 'PENDING' AND attempt_no = 0 AND error_code IS NULL)
        OR
        (event_kind = 'SUCCEEDED' AND attempt_no > 0 AND error_code IS NULL)
        OR
        (event_kind IN ('RETRYING','DELIVERY_FAILED')
            AND attempt_no > 0 AND error_code IS NOT NULL))
);
