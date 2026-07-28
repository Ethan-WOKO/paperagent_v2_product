CREATE TABLE agent_v2_effect_execution_claims (
    tool_call_id VARCHAR(128) NOT NULL,
    plan_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    activation_event_id VARCHAR(128) NOT NULL,
    claimed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tool_call_id),
    CONSTRAINT fk_agent_v2_effect_execution_claim_intent
        FOREIGN KEY (tool_call_id, plan_id, step_id, activation_event_id)
        REFERENCES agent_v2_effect_intents
            (tool_call_id, plan_id, step_id, activation_event_id)
);
