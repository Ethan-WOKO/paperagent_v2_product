CREATE INDEX idx_agent_context_snapshots_turn
    ON agent_context_snapshots (turn_id);

ALTER TABLE agent_context_snapshots
    DROP CONSTRAINT uq_agent_context_snapshots_turn;

ALTER TABLE agent_context_snapshots ADD COLUMN revision_number INT;
ALTER TABLE agent_context_snapshots ADD COLUMN parent_snapshot_id BIGINT;
ALTER TABLE agent_context_snapshots ADD COLUMN context_stage VARCHAR(32);
ALTER TABLE agent_context_snapshots ADD COLUMN stable_stage_key VARCHAR(255);
ALTER TABLE agent_context_snapshots ADD COLUMN revision_status VARCHAR(32);
ALTER TABLE agent_context_snapshots ADD COLUMN model_provider_snapshot VARCHAR(64);
ALTER TABLE agent_context_snapshots ADD COLUMN model_snapshot VARCHAR(128);
ALTER TABLE agent_context_snapshots ADD COLUMN context_window_tokens BIGINT;
ALTER TABLE agent_context_snapshots ADD COLUMN max_output_tokens BIGINT;
ALTER TABLE agent_context_snapshots ADD COLUMN token_counter_version VARCHAR(64);
ALTER TABLE agent_context_snapshots ADD COLUMN profile_version VARCHAR(64);
ALTER TABLE agent_context_snapshots ADD COLUMN total_tokens BIGINT;
ALTER TABLE agent_context_snapshots ADD COLUMN output_reserve_tokens BIGINT;
ALTER TABLE agent_context_snapshots ADD COLUMN parent_digest CHAR(64);
ALTER TABLE agent_context_snapshots ADD COLUMN context_digest CHAR(64);

UPDATE agent_context_snapshots SET revision_number = 1
WHERE revision_number IS NULL;

ALTER TABLE agent_context_snapshots ALTER COLUMN revision_number SET NOT NULL;
ALTER TABLE agent_context_snapshots ADD CONSTRAINT uq_agent_context_snapshots_turn_revision
    UNIQUE (turn_id, revision_number);
ALTER TABLE agent_context_snapshots ADD CONSTRAINT uq_agent_context_snapshots_owner_stage
    UNIQUE (user_id, session_id, turn_id, stable_stage_key);
ALTER TABLE agent_context_snapshots ADD CONSTRAINT fk_agent_context_snapshots_parent
    FOREIGN KEY (parent_snapshot_id) REFERENCES agent_context_snapshots (id);

CREATE TABLE agent_context_snapshot_sections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    section_ordinal INT NOT NULL,
    section_type VARCHAR(64) NOT NULL,
    fixed_percentage INT NOT NULL,
    token_limit BIGINT NOT NULL,
    tokens_before BIGINT NOT NULL,
    tokens_after BIGINT NOT NULL,
    section_status VARCHAR(32) NOT NULL,
    source_refs_json LONGTEXT NOT NULL,
    projection_json LONGTEXT NOT NULL,
    projection_digest CHAR(64) NOT NULL,
    compaction_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_agent_context_section_type UNIQUE (snapshot_id, section_type),
    CONSTRAINT uq_agent_context_section_ordinal UNIQUE (snapshot_id, section_ordinal),
    CONSTRAINT fk_agent_context_sections_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES agent_context_snapshots (id)
        ON DELETE CASCADE
);
CREATE INDEX idx_agent_context_sections_snapshot
    ON agent_context_snapshot_sections (snapshot_id);
