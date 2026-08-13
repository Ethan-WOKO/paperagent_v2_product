ALTER TABLE agent_v2_chain_plan_bindings
    DROP CONSTRAINT uk_chain_plan_binding_plan;

ALTER TABLE agent_v2_chain_plan_bindings
    ADD CONSTRAINT uk_chain_plan_binding_revision
        UNIQUE (plan_id, plan_revision_id);
