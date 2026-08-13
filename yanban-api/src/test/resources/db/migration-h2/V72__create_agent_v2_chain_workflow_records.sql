CREATE TABLE agent_v2_chain_transitions (
    transition_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    transition_type VARCHAR(48) NOT NULL,
    source_decision_id VARCHAR(128) NOT NULL,
    target_identity_digest CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (transition_id),
    CONSTRAINT uk_chain_transition_identity
        UNIQUE (task_id, transition_type, source_decision_id,
            target_identity_digest),
    CONSTRAINT uk_chain_transition_event UNIQUE (event_id),
    CONSTRAINT uk_chain_transition_task_identity
        UNIQUE (task_id, transition_id),
    CONSTRAINT fk_chain_transition_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_transition_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_transition_type CHECK (transition_type IN (
        'GAP_RESOLUTION','ACCEPT_STEP','PLAN_CHANGE',
        'FINAL_STEP_READINESS','FINALIZATION')),
    CONSTRAINT ck_chain_transition_target_hash
        CHECK (REGEXP_LIKE(target_identity_digest, '^[0-9a-f]{64}$'))
);

CREATE INDEX idx_chain_transition_task_created
    ON agent_v2_chain_transitions (task_id, created_at);

CREATE TABLE agent_v2_chain_transition_stages (
    transition_id VARCHAR(128) NOT NULL,
    stage_code VARCHAR(64) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    stage_ordinal INT NOT NULL,
    predecessor_authority_type VARCHAR(64),
    predecessor_authority_ref VARCHAR(128),
    successor_authority_type VARCHAR(64),
    successor_authority_ref VARCHAR(128),
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (transition_id, stage_code),
    CONSTRAINT uk_chain_transition_stage_ordinal
        UNIQUE (transition_id, stage_ordinal),
    CONSTRAINT uk_chain_transition_stage_event UNIQUE (event_id),
    CONSTRAINT fk_chain_transition_stage_transition
        FOREIGN KEY (task_id, transition_id)
        REFERENCES agent_v2_chain_transitions(task_id, transition_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_transition_stage_event
        FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_transition_stage_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_transition_stage_ordinal CHECK (stage_ordinal >= 0),
    CONSTRAINT ck_chain_transition_stage_refs CHECK (
        ((predecessor_authority_type IS NULL
            AND predecessor_authority_ref IS NULL)
         OR (predecessor_authority_type IS NOT NULL
            AND predecessor_authority_ref IS NOT NULL))
        AND
        ((successor_authority_type IS NULL
            AND successor_authority_ref IS NULL)
         OR (successor_authority_type IS NOT NULL
            AND successor_authority_ref IS NOT NULL)))
);

CREATE TABLE agent_v2_chain_route_decisions (
    route_decision_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    proposal_id VARCHAR(128) NOT NULL,
    decision_kind VARCHAR(16) NOT NULL,
    decision_ordinal INT NOT NULL,
    route VARCHAR(32) NOT NULL,
    route_reason VARCHAR(2000) NOT NULL,
    direct_task_spec_format_version INT,
    direct_task_spec_sha256 CHAR(64),
    direct_task_spec_json CLOB,
    user_constraints_format_version INT,
    user_constraints_sha256 CHAR(64),
    user_constraints_json CLOB,
    answer_required_refs_format_version INT,
    answer_required_refs_sha256 CHAR(64),
    answer_required_refs_json CLOB,
    needs_tool SMALLINT NOT NULL,
    needs_network SMALLINT NOT NULL,
    needs_project SMALLINT NOT NULL,
    needs_persistent_progress SMALLINT NOT NULL,
    parent_route_decision_id VARCHAR(128),
    escalation_reason VARCHAR(2000),
    transition_id VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (route_decision_id),
    CONSTRAINT uk_chain_route_task_instruction_kind
        UNIQUE (task_id, instruction_id, decision_kind),
    CONSTRAINT uk_chain_route_event UNIQUE (event_id),
    CONSTRAINT uk_chain_route_task_identity
        UNIQUE (task_id, route_decision_id),
    CONSTRAINT fk_chain_route_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_route_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_route_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT fk_chain_route_proposal FOREIGN KEY (task_id, proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_route_parent
        FOREIGN KEY (task_id, parent_route_decision_id)
        REFERENCES agent_v2_chain_route_decisions(task_id, route_decision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_route_transition FOREIGN KEY (task_id, transition_id)
        REFERENCES agent_v2_chain_transitions(task_id, transition_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_route_kind
        CHECK (decision_kind IN ('INITIAL','ESCALATION')),
    CONSTRAINT ck_chain_route_value
        CHECK (route IN ('DIRECT','PERSISTENT_PLAN_EXECUTE')),
    CONSTRAINT ck_chain_route_flags CHECK (
        needs_tool IN (0,1) AND needs_network IN (0,1)
        AND needs_project IN (0,1)
        AND needs_persistent_progress IN (0,1)),
    CONSTRAINT ck_chain_route_decision_shape CHECK (
        (decision_kind = 'INITIAL' AND decision_ordinal = 0
            AND parent_route_decision_id IS NULL AND escalation_reason IS NULL)
        OR
        (decision_kind = 'ESCALATION' AND decision_ordinal = 1
            AND parent_route_decision_id IS NOT NULL
            AND escalation_reason IS NOT NULL
            AND route = 'PERSISTENT_PLAN_EXECUTE')),
    CONSTRAINT ck_chain_route_direct_shape CHECK (
        route <> 'DIRECT'
        OR
        (needs_tool = 0 AND needs_network = 0 AND needs_project = 0
            AND needs_persistent_progress = 0
            AND direct_task_spec_format_version IS NOT NULL
            AND direct_task_spec_format_version = 1
            AND direct_task_spec_sha256 IS NOT NULL
            AND REGEXP_LIKE(direct_task_spec_sha256, '^[0-9a-f]{64}$')
            AND direct_task_spec_json IS NOT NULL
            AND user_constraints_format_version IS NOT NULL
            AND user_constraints_format_version = 1
            AND user_constraints_sha256 IS NOT NULL
            AND REGEXP_LIKE(user_constraints_sha256, '^[0-9a-f]{64}$')
            AND user_constraints_json IS NOT NULL
            AND answer_required_refs_format_version IS NOT NULL
            AND answer_required_refs_format_version = 1
            AND answer_required_refs_sha256 IS NOT NULL
            AND REGEXP_LIKE(answer_required_refs_sha256, '^[0-9a-f]{64}$')
            AND answer_required_refs_json IS NOT NULL)),
    CONSTRAINT ck_chain_route_optional_json CHECK (
        ((direct_task_spec_format_version IS NULL
            AND direct_task_spec_sha256 IS NULL
            AND direct_task_spec_json IS NULL)
         OR (direct_task_spec_format_version IS NOT NULL
            AND direct_task_spec_format_version = 1
            AND direct_task_spec_sha256 IS NOT NULL
            AND REGEXP_LIKE(direct_task_spec_sha256, '^[0-9a-f]{64}$')
            AND direct_task_spec_json IS NOT NULL))
        AND
        ((user_constraints_format_version IS NULL
            AND user_constraints_sha256 IS NULL
            AND user_constraints_json IS NULL)
         OR (user_constraints_format_version IS NOT NULL
            AND user_constraints_format_version = 1
            AND user_constraints_sha256 IS NOT NULL
            AND REGEXP_LIKE(user_constraints_sha256, '^[0-9a-f]{64}$')
            AND user_constraints_json IS NOT NULL))
        AND
        ((answer_required_refs_format_version IS NULL
            AND answer_required_refs_sha256 IS NULL
            AND answer_required_refs_json IS NULL)
         OR (answer_required_refs_format_version IS NOT NULL
            AND answer_required_refs_format_version = 1
            AND answer_required_refs_sha256 IS NOT NULL
            AND REGEXP_LIKE(answer_required_refs_sha256, '^[0-9a-f]{64}$')
            AND answer_required_refs_json IS NOT NULL)))
);

CREATE TABLE agent_v2_chain_instruction_dispositions (
    disposition_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    proposal_id VARCHAR(128) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    classification VARCHAR(64) NOT NULL,
    old_task_disposition VARCHAR(64) NOT NULL,
    reply_required SMALLINT NOT NULL,
    continuation_or_reintake_position VARCHAR(2000) NOT NULL,
    boundary_changed SMALLINT NOT NULL,
    applicability_format_version INT NOT NULL,
    applicability_sha256 CHAR(64) NOT NULL,
    applicability_json CLOB NOT NULL,
    non_authoritative_reuse_suggestions_format_version INT NOT NULL,
    non_authoritative_reuse_suggestions_sha256 CHAR(64) NOT NULL,
    non_authoritative_reuse_suggestions_json CLOB NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (disposition_id),
    CONSTRAINT uk_chain_disposition_event UNIQUE (event_id),
    CONSTRAINT uk_chain_disposition_task_identity UNIQUE (task_id, disposition_id),
    CONSTRAINT uk_chain_disposition_instruction UNIQUE (task_id, instruction_id),
    CONSTRAINT fk_chain_disposition_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_disposition_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_disposition_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT fk_chain_disposition_proposal FOREIGN KEY (task_id, proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_disposition_flags CHECK (
        reply_required IN (0,1) AND boundary_changed IN (0,1)),
    CONSTRAINT ck_chain_disposition_json CHECK (
        applicability_format_version = 1
        AND applicability_sha256 REGEXP '^[0-9a-f]{64}$'
        AND non_authoritative_reuse_suggestions_format_version = 1
        AND non_authoritative_reuse_suggestions_sha256 REGEXP '^[0-9a-f]{64}$')
);

CREATE INDEX idx_chain_disposition_task_created
    ON agent_v2_chain_instruction_dispositions (task_id, created_at);

CREATE TABLE agent_v2_chain_plan_bindings (
    plan_binding_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    route_decision_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    plan_revision_number BIGINT NOT NULL,
    authority_type VARCHAR(64) NOT NULL,
    authority_id VARCHAR(128) NOT NULL,
    authority_sha256 CHAR(64) NOT NULL,
    transition_id VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (plan_binding_id),
    CONSTRAINT uk_chain_plan_binding_plan UNIQUE (plan_id),
    CONSTRAINT uk_chain_plan_binding_event UNIQUE (event_id),
    CONSTRAINT uk_chain_plan_binding_task_identity
        UNIQUE (task_id, plan_binding_id),
    CONSTRAINT fk_chain_plan_binding_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_plan_binding_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_plan_binding_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT fk_chain_plan_binding_route
        FOREIGN KEY (task_id, route_decision_id)
        REFERENCES agent_v2_chain_route_decisions(task_id, route_decision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_plan_binding_transition
        FOREIGN KEY (task_id, transition_id)
        REFERENCES agent_v2_chain_transitions(task_id, transition_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_plan_binding_revision
        CHECK (plan_revision_number > 0),
    CONSTRAINT ck_chain_plan_binding_hash
        CHECK (REGEXP_LIKE(authority_sha256, '^[0-9a-f]{64}$'))
);

CREATE INDEX idx_chain_plan_binding_task
    ON agent_v2_chain_plan_bindings (task_id, created_at);

CREATE TABLE agent_v2_chain_candidate_step_results (
    candidate_result_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    proposal_id VARCHAR(128) NOT NULL,
    content_id VARCHAR(128) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    plan_revision_number BIGINT NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    artifact_id BIGINT,
    candidate_fingerprint CHAR(64),
    diff_digest CHAR(64),
    receipt_refs_format_version INT NOT NULL,
    receipt_refs_sha256 CHAR(64) NOT NULL,
    receipt_refs_json CLOB NOT NULL,
    validation_id VARCHAR(128),
    validation_request_digest CHAR(64),
    validation_receipt_digest CHAR(64),
    evidence_refs_format_version INT NOT NULL,
    evidence_refs_sha256 CHAR(64) NOT NULL,
    evidence_refs_json CLOB NOT NULL,
    version_fence_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (candidate_result_id),
    CONSTRAINT uk_chain_candidate_result_proposal UNIQUE (proposal_id),
    CONSTRAINT uk_chain_candidate_result_event UNIQUE (event_id),
    CONSTRAINT uk_chain_candidate_result_task_identity
        UNIQUE (task_id, candidate_result_id),
    CONSTRAINT fk_chain_candidate_result_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_candidate_result_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_candidate_result_proposal
        FOREIGN KEY (task_id, proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_candidate_result_content
        FOREIGN KEY (task_id, content_id)
        REFERENCES agent_v2_chain_contents(task_id, content_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_candidate_result_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_candidate_result_revision
        CHECK (plan_revision_number > 0),
    CONSTRAINT ck_chain_candidate_result_candidate CHECK (
        (artifact_id IS NULL AND candidate_fingerprint IS NULL
            AND diff_digest IS NULL)
        OR
        (artifact_id IS NOT NULL
            AND candidate_fingerprint IS NOT NULL AND diff_digest IS NOT NULL
            AND REGEXP_LIKE(candidate_fingerprint, '^[0-9a-f]{64}$')
            AND REGEXP_LIKE(diff_digest, '^[0-9a-f]{64}$'))),
    CONSTRAINT ck_chain_candidate_result_validation CHECK (
        (validation_id IS NULL AND validation_request_digest IS NULL
            AND validation_receipt_digest IS NULL)
        OR
        (validation_id IS NOT NULL
            AND validation_request_digest IS NOT NULL
            AND validation_receipt_digest IS NOT NULL
            AND REGEXP_LIKE(validation_request_digest, '^[0-9a-f]{64}$')
            AND REGEXP_LIKE(validation_receipt_digest, '^[0-9a-f]{64}$'))),
    CONSTRAINT ck_chain_candidate_result_formats CHECK (
        receipt_refs_format_version = 1 AND evidence_refs_format_version = 1),
    CONSTRAINT ck_chain_candidate_result_hashes CHECK (
        REGEXP_LIKE(receipt_refs_sha256, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(evidence_refs_sha256, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(version_fence_sha256, '^[0-9a-f]{64}$'))
);

CREATE TABLE agent_v2_chain_review_decisions (
    review_decision_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    proposal_id VARCHAR(128) NOT NULL,
    review_object_type VARCHAR(64) NOT NULL,
    review_object_id VARCHAR(128) NOT NULL,
    decision_kind VARCHAR(64) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    fact_refs_format_version INT NOT NULL,
    fact_refs_sha256 CHAR(64) NOT NULL,
    fact_refs_json CLOB NOT NULL,
    version_fence_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (review_decision_id),
    CONSTRAINT uk_chain_review_proposal UNIQUE (proposal_id),
    CONSTRAINT uk_chain_review_event UNIQUE (event_id),
    CONSTRAINT uk_chain_review_task_identity
        UNIQUE (task_id, review_decision_id),
    CONSTRAINT fk_chain_review_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_review_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_review_proposal FOREIGN KEY (task_id, proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_review_kind CHECK (decision_kind IN (
        'CONTINUE_STEP','ACCEPT_STEP','ACCEPT_STEP_AND_READY_TO_FINALIZE',
        'REPLAN_REQUIRED','NEED_USER_INPUT','NEED_PERMISSION',
        'READY_TO_FINALIZE','TASK_FAILED')),
    CONSTRAINT ck_chain_review_format CHECK (fact_refs_format_version = 1),
    CONSTRAINT ck_chain_review_hashes CHECK (
        REGEXP_LIKE(fact_refs_sha256, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(version_fence_sha256, '^[0-9a-f]{64}$'))
);

CREATE TABLE agent_v2_chain_accepted_results (
    accepted_result_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    candidate_result_id VARCHAR(128) NOT NULL,
    review_decision_id VARCHAR(128) NOT NULL,
    transition_id VARCHAR(128) NOT NULL,
    content_id VARCHAR(128) NOT NULL,
    accepted_identity_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (accepted_result_id),
    CONSTRAINT uk_chain_accepted_candidate_result UNIQUE (candidate_result_id),
    CONSTRAINT uk_chain_accepted_event UNIQUE (event_id),
    CONSTRAINT uk_chain_accepted_task_identity
        UNIQUE (task_id, accepted_result_id),
    CONSTRAINT fk_chain_accepted_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_accepted_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_accepted_candidate_result
        FOREIGN KEY (task_id, candidate_result_id)
        REFERENCES agent_v2_chain_candidate_step_results(
            task_id, candidate_result_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_accepted_review FOREIGN KEY (task_id, review_decision_id)
        REFERENCES agent_v2_chain_review_decisions(task_id, review_decision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_accepted_transition FOREIGN KEY (task_id, transition_id)
        REFERENCES agent_v2_chain_transitions(task_id, transition_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_accepted_content FOREIGN KEY (task_id, content_id)
        REFERENCES agent_v2_chain_contents(task_id, content_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_accepted_identity_hash
        CHECK (REGEXP_LIKE(accepted_identity_sha256, '^[0-9a-f]{64}$'))
);

CREATE TABLE agent_v2_chain_result_applicability (
    applicability_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    accepted_result_id VARCHAR(128) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_decision_id VARCHAR(128) NOT NULL,
    target_task_frame_id VARCHAR(128) NOT NULL,
    target_plan_id VARCHAR(128) NOT NULL,
    target_plan_revision_id VARCHAR(128) NOT NULL,
    target_candidate_key VARCHAR(128) NOT NULL,
    target_instruction_version_id VARCHAR(128) NOT NULL,
    conclusion VARCHAR(32) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (applicability_id),
    CONSTRAINT uk_chain_applicability_tuple UNIQUE (
        accepted_result_id, source_type, source_decision_id,
        target_task_frame_id, target_plan_id, target_plan_revision_id,
        target_candidate_key, target_instruction_version_id),
    CONSTRAINT uk_chain_applicability_event UNIQUE (event_id),
    CONSTRAINT fk_chain_applicability_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_applicability_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_applicability_accepted
        FOREIGN KEY (task_id, accepted_result_id)
        REFERENCES agent_v2_chain_accepted_results(task_id, accepted_result_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_applicability_source CHECK (source_type IN (
        'ACCEPT_STEP','PLAN_REVISION','USER_INSTRUCTION_DISPOSITION',
        'PERSISTENT_PLAN')),
    CONSTRAINT ck_chain_applicability_conclusion
        CHECK (conclusion IN ('APPLICABLE','NOT_APPLICABLE')),
    CONSTRAINT ck_chain_applicability_candidate
        CHECK (CHAR_LENGTH(TRIM(target_candidate_key)) > 0),
    CONSTRAINT ck_chain_applicability_instruction CHECK (
        CHAR_LENGTH(TRIM(target_instruction_version_id)) > 0
        AND (source_type <> 'USER_INSTRUCTION_DISPOSITION'
            OR target_instruction_version_id <> 'NONE'))
);

CREATE INDEX idx_chain_applicability_task_event
    ON agent_v2_chain_result_applicability (task_id, event_id);

CREATE TABLE agent_v2_chain_pending_items (
    gap_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    source_proposal_id VARCHAR(128) NOT NULL,
    pending_type VARCHAR(32) NOT NULL,
    gap_identity_sha256 CHAR(64) NOT NULL,
    missing_fields_format_version INT NOT NULL,
    missing_fields_sha256 CHAR(64) NOT NULL,
    missing_fields_json CLOB NOT NULL,
    permission_scope VARCHAR(255),
    question CLOB NOT NULL,
    expected_format VARCHAR(1000) NOT NULL,
    validation_role VARCHAR(32) NOT NULL,
    resume_role VARCHAR(32) NOT NULL,
    resume_position_format_version INT NOT NULL,
    resume_position_sha256 CHAR(64) NOT NULL,
    resume_position_json CLOB NOT NULL,
    version_fence_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (gap_id),
    CONSTRAINT uk_chain_pending_identity
        UNIQUE (task_id, gap_identity_sha256),
    CONSTRAINT uk_chain_pending_event UNIQUE (event_id),
    CONSTRAINT uk_chain_pending_task_identity UNIQUE (task_id, gap_id),
    CONSTRAINT fk_chain_pending_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_pending_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_pending_proposal
        FOREIGN KEY (task_id, source_proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_pending_type
        CHECK (pending_type IN ('USER_INFORMATION','USER_CHOICE','PERMISSION')),
    CONSTRAINT ck_chain_pending_permission CHECK (
        (pending_type = 'PERMISSION' AND permission_scope IS NOT NULL)
        OR
        (pending_type <> 'PERMISSION' AND permission_scope IS NULL)),
    CONSTRAINT ck_chain_pending_roles CHECK (
        validation_role IN ('PLANNER','EXECUTOR')
        AND resume_role IN ('PLANNER','EXECUTOR','REFLECTOR','ANSWER')),
    CONSTRAINT ck_chain_pending_formats CHECK (
        missing_fields_format_version = 1
        AND resume_position_format_version = 1),
    CONSTRAINT ck_chain_pending_hashes CHECK (
        REGEXP_LIKE(gap_identity_sha256, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(missing_fields_sha256, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(resume_position_sha256, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(version_fence_sha256, '^[0-9a-f]{64}$'))
);

CREATE TABLE agent_v2_chain_pending_item_events (
    gap_id VARCHAR(128) NOT NULL,
    response_round INT NOT NULL,
    event_kind VARCHAR(32) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    answer_instruction_id VARCHAR(128),
    validation_invocation_id VARCHAR(128),
    gap_validation_outcome VARCHAR(32),
    detail_format_version INT NOT NULL,
    detail_sha256 CHAR(64) NOT NULL,
    detail_json CLOB NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (gap_id, response_round, event_kind),
    CONSTRAINT uk_chain_pending_item_event UNIQUE (event_id),
    CONSTRAINT fk_chain_pending_item_gap FOREIGN KEY (task_id, gap_id)
        REFERENCES agent_v2_chain_pending_items(task_id, gap_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_pending_item_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_pending_item_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_pending_item_instruction
        FOREIGN KEY (answer_instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT fk_chain_pending_item_invocation
        FOREIGN KEY (task_id, validation_invocation_id)
        REFERENCES agent_v2_chain_model_invocations(task_id, invocation_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_pending_item_round CHECK (response_round >= 0),
    CONSTRAINT ck_chain_pending_item_kind CHECK (event_kind IN (
        'PENDING','RESPONSE_RECEIVED','RESOLVED','REJECTED','CANCELLED')),
    CONSTRAINT ck_chain_pending_item_answer CHECK (
        event_kind <> 'RESPONSE_RECEIVED' OR answer_instruction_id IS NOT NULL),
    CONSTRAINT ck_chain_pending_item_validation CHECK (
        gap_validation_outcome IS NULL
        OR gap_validation_outcome IN ('RESOLVED','STILL_PENDING')),
    CONSTRAINT ck_chain_pending_item_detail CHECK (
        detail_format_version = 1
        AND REGEXP_LIKE(detail_sha256, '^[0-9a-f]{64}$'))
);

CREATE TABLE agent_v2_chain_permission_decisions (
    permission_decision_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    gap_id VARCHAR(128) NOT NULL,
    permission_scope VARCHAR(255) NOT NULL,
    product_authority_type VARCHAR(64) NOT NULL,
    product_authority_ref VARCHAR(128) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (permission_decision_id),
    CONSTRAINT uk_chain_permission_gap UNIQUE (gap_id),
    CONSTRAINT uk_chain_permission_event UNIQUE (event_id),
    CONSTRAINT fk_chain_permission_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_permission_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_permission_gap FOREIGN KEY (task_id, gap_id)
        REFERENCES agent_v2_chain_pending_items(task_id, gap_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_permission_decision
        CHECK (decision IN ('GRANTED','DENIED'))
);

CREATE TABLE agent_v2_chain_action_bindings (
    action_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    proposal_id VARCHAR(128) NOT NULL,
    attempt_no INT NOT NULL,
    action_signature_sha256 CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    instruction_id VARCHAR(128) NOT NULL,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    base_candidate_key VARCHAR(128) NOT NULL,
    effect_intent_id VARCHAR(128),
    dispatch_ref VARCHAR(128),
    result_authority_type VARCHAR(64),
    result_authority_ref VARCHAR(128),
    version_fence_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (action_id),
    CONSTRAINT uk_chain_action_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_chain_action_attempt
        UNIQUE (plan_id, step_id, activation_event_id, attempt_no),
    CONSTRAINT uk_chain_action_event UNIQUE (event_id),
    CONSTRAINT uk_chain_action_task_identity UNIQUE (task_id, action_id),
    CONSTRAINT fk_chain_action_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_event FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_proposal FOREIGN KEY (task_id, proposal_id)
        REFERENCES agent_v2_chain_model_proposals(task_id, proposal_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_action_instruction FOREIGN KEY (instruction_id)
        REFERENCES agent_v2_chain_instructions(instruction_id),
    CONSTRAINT ck_chain_action_attempt CHECK (attempt_no > 0),
    CONSTRAINT ck_chain_action_signature
        CHECK (REGEXP_LIKE(action_signature_sha256, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_chain_action_result_ref CHECK (
        (result_authority_type IS NULL AND result_authority_ref IS NULL)
        OR
        (result_authority_type IS NOT NULL AND result_authority_ref IS NOT NULL)),
    CONSTRAINT ck_chain_action_version_fence
        CHECK (REGEXP_LIKE(version_fence_sha256, '^[0-9a-f]{64}$'))
);

CREATE TABLE agent_v2_chain_workspace_candidates (
    workspace_candidate_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128) NOT NULL,
    base_project_version VARCHAR(255) NOT NULL,
    artifact_id BIGINT NOT NULL,
    candidate_fingerprint CHAR(64) NOT NULL,
    diff_digest CHAR(64) NOT NULL,
    version_fence_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (workspace_candidate_id),
    CONSTRAINT uk_chain_workspace_candidate_action UNIQUE (action_id),
    CONSTRAINT uk_chain_workspace_candidate_event UNIQUE (event_id),
    CONSTRAINT fk_chain_workspace_candidate_task FOREIGN KEY (task_id)
        REFERENCES agent_v2_chain_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_workspace_candidate_event
        FOREIGN KEY (task_id, event_id)
        REFERENCES agent_v2_chain_authority_events(task_id, event_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chain_workspace_candidate_action
        FOREIGN KEY (task_id, action_id)
        REFERENCES agent_v2_chain_action_bindings(task_id, action_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chain_workspace_candidate_hashes CHECK (
        REGEXP_LIKE(candidate_fingerprint, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(diff_digest, '^[0-9a-f]{64}$')
        AND REGEXP_LIKE(version_fence_sha256, '^[0-9a-f]{64}$'))
);
