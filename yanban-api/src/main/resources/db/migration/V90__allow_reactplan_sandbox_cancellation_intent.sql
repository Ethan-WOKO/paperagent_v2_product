ALTER TABLE reactplan_sandbox_executions
    DROP CONSTRAINT ck_engine_sandbox_state;

ALTER TABLE reactplan_sandbox_executions
    ADD CONSTRAINT ck_engine_sandbox_state CHECK (
        state IN ('QUEUED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED',
                  'TIMED_OUT','CANCELLED','SYSTEM_ERROR'));
