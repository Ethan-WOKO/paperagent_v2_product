ALTER TABLE agent_v2_literature_deliveries
    ADD COLUMN literature_task_id BIGINT;

ALTER TABLE agent_v2_literature_deliveries
    ADD COLUMN result_assistant_message_id BIGINT;

ALTER TABLE agent_v2_literature_deliveries
    ADD CONSTRAINT uk_agent_v2_delivery_literature_task
        UNIQUE (literature_task_id);

ALTER TABLE agent_v2_literature_deliveries
    ADD CONSTRAINT uk_agent_v2_delivery_result_message
        UNIQUE (result_assistant_message_id);
