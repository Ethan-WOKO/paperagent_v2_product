ALTER TABLE agent_v2_turn_intakes
    ADD COLUMN history_visible BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_agent_v2_turn_history
    ON agent_v2_turn_intakes(
        user_id, session_id, history_visible, created_at, id);
