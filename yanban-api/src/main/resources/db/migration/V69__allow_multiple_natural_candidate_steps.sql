ALTER TABLE agent_v2_natural_candidate_authorities
    DROP INDEX uk_agent_v2_natural_candidate_plan;

ALTER TABLE agent_v2_natural_candidate_authorities
    DROP INDEX uk_agent_v2_natural_candidate_step;

CREATE UNIQUE INDEX uk_agent_v2_natural_candidate_attempt
    ON agent_v2_natural_candidate_authorities
        (plan_id, step_id, authority_sha256);

CREATE INDEX idx_agent_v2_natural_candidate_plan_latest
    ON agent_v2_natural_candidate_authorities (plan_id, id);

CREATE INDEX idx_agent_v2_natural_candidate_step_latest
    ON agent_v2_natural_candidate_authorities (plan_id, step_id, id);
