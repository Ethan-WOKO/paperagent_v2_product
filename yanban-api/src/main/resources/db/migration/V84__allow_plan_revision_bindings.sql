ALTER TABLE agent_v2_chain_plan_bindings
    DROP INDEX uk_chain_plan_binding_plan,
    ADD CONSTRAINT uk_chain_plan_binding_revision
        UNIQUE (plan_id, plan_revision_id);
