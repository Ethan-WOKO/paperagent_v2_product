ALTER TABLE agent_context_snapshots
    ADD INDEX idx_agent_context_snapshots_turn (turn_id);

ALTER TABLE agent_context_snapshots
    DROP INDEX uq_agent_context_snapshots_turn,
    ADD COLUMN revision_number INT NULL AFTER user_id,
    ADD COLUMN parent_snapshot_id BIGINT NULL AFTER revision_number,
    ADD COLUMN context_stage VARCHAR(32) NULL AFTER parent_snapshot_id,
    ADD COLUMN stable_stage_key VARCHAR(255) NULL AFTER context_stage,
    ADD COLUMN revision_status VARCHAR(32) NULL AFTER stable_stage_key,
    ADD COLUMN model_provider_snapshot VARCHAR(64) NULL AFTER revision_status,
    ADD COLUMN model_snapshot VARCHAR(128) NULL AFTER model_provider_snapshot,
    ADD COLUMN context_window_tokens BIGINT NULL AFTER model_snapshot,
    ADD COLUMN max_output_tokens BIGINT NULL AFTER context_window_tokens,
    ADD COLUMN token_counter_version VARCHAR(64) NULL AFTER max_output_tokens,
    ADD COLUMN profile_version VARCHAR(64) NULL AFTER token_counter_version,
    ADD COLUMN total_tokens BIGINT NULL AFTER profile_version,
    ADD COLUMN output_reserve_tokens BIGINT NULL AFTER total_tokens,
    ADD COLUMN parent_digest CHAR(64) NULL AFTER output_reserve_tokens,
    ADD COLUMN context_digest CHAR(64) NULL AFTER parent_digest;

UPDATE agent_context_snapshots
SET revision_number = 1
WHERE revision_number IS NULL;

ALTER TABLE agent_context_snapshots
    MODIFY COLUMN revision_number INT NOT NULL,
    ADD CONSTRAINT uq_agent_context_snapshots_turn_revision
        UNIQUE (turn_id, revision_number),
    ADD CONSTRAINT uq_agent_context_snapshots_owner_stage
        UNIQUE (user_id, session_id, turn_id, stable_stage_key),
    ADD CONSTRAINT fk_agent_context_snapshots_parent
        FOREIGN KEY (parent_snapshot_id) REFERENCES agent_context_snapshots (id)
        ON DELETE RESTRICT;

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
    compaction_reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_agent_context_section_type UNIQUE (snapshot_id, section_type),
    CONSTRAINT uq_agent_context_section_ordinal UNIQUE (snapshot_id, section_ordinal),
    INDEX idx_agent_context_sections_snapshot (snapshot_id),
    CONSTRAINT fk_agent_context_sections_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES agent_context_snapshots (id)
        ON DELETE CASCADE
);
