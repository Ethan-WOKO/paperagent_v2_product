ALTER TABLE agent_v2_step_completions
    DROP CHECK ck_agent_v2_step_completion_formats,
    ADD CONSTRAINT ck_agent_v2_step_completion_formats CHECK (
        (request_format_version = 1 AND result_format_version = 1)
        OR (request_format_version = 2 AND result_format_version = 2));
