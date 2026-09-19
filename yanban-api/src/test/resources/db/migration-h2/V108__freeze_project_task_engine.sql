ALTER TABLE reactplan_turn_intakes ADD COLUMN engine VARCHAR(16) NOT NULL DEFAULT 'TS';
CREATE INDEX idx_reactplan_intake_engine_task ON reactplan_turn_intakes(engine, task_id);
