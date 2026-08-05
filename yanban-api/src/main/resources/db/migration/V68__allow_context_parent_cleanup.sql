ALTER TABLE agent_context_snapshots
    DROP CONSTRAINT fk_agent_context_snapshots_parent;

ALTER TABLE agent_context_snapshots
    ADD CONSTRAINT fk_agent_context_snapshots_parent
        FOREIGN KEY (parent_snapshot_id) REFERENCES agent_context_snapshots (id)
        ON DELETE SET NULL;
