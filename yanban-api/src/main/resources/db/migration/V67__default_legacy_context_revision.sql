ALTER TABLE agent_context_snapshots
    MODIFY COLUMN revision_number INT NOT NULL DEFAULT 1;
