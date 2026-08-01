ALTER TABLE agent_v2_turn_intakes
    ADD COLUMN model_provider_snapshot VARCHAR(64);

ALTER TABLE agent_v2_turn_intakes
    ADD COLUMN model_snapshot VARCHAR(128);
