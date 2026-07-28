ALTER TABLE agent_v2_step_activations
    DROP CONSTRAINT ck_agent_v2_step_activations_source;

ALTER TABLE agent_v2_step_activations
    DROP CONSTRAINT ck_agent_v2_step_activations_result;

ALTER TABLE agent_v2_step_activations
    ADD CONSTRAINT ck_agent_v2_step_activations_progression CHECK (
        source_revision_number > 0
        AND result_revision_id = source_revision_id
        AND result_revision_number = source_revision_number
        AND source_checkpoint_version >= 2
        AND result_checkpoint_version = source_checkpoint_version + 1
        AND source_event_sequence >= 1
        AND result_event_sequence = source_event_sequence + 1);

ALTER TABLE agent_v2_step_activations
    ADD CONSTRAINT uk_agent_v2_step_activation_step
        UNIQUE (plan_id, step_id);

ALTER TABLE agent_v2_step_completions
    DROP INDEX uk_agent_v2_step_completions_plan;

ALTER TABLE agent_v2_step_completions
    DROP CONSTRAINT ck_agent_v2_step_completion_versions;

ALTER TABLE agent_v2_step_completions
    ADD CONSTRAINT ck_agent_v2_step_completion_progression CHECK (
        source_revision_number > 0
        AND result_revision_number = source_revision_number + 1
        AND source_checkpoint_version >= 3
        AND result_checkpoint_version = source_checkpoint_version + 1
        AND source_event_sequence >= 2
        AND result_event_sequence = source_event_sequence + 1
        AND fencing_token > 0);

ALTER TABLE agent_v2_step_completions
    ADD CONSTRAINT uk_agent_v2_step_completion_activation
        UNIQUE (plan_id, step_id, activation_event_id);
