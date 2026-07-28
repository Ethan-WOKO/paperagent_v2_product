CREATE TABLE agent_v2_final_syntheses (
    plan_id VARCHAR(128) PRIMARY KEY,
    synthesis_id VARCHAR(128) NOT NULL UNIQUE,
    task_frame_id VARCHAR(128) NOT NULL,
    plan_revision_id VARCHAR(128) NOT NULL,
    receipt_ids_json CLOB NOT NULL,
    narrative CLOB NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    canonical_sha256 VARCHAR(64) NOT NULL,
    assistant_message_id BIGINT,
    committed_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE agent_v2_literature_deliveries (
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    query_text VARCHAR(1000) NOT NULL,
    top_k INT NOT NULL,
    year_from INT,
    include_bibtex BOOLEAN NOT NULL,
    user_message_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    lease_owner_id VARCHAR(128) NOT NULL,
    lease_token VARCHAR(128) NOT NULL,
    lease_expires_at TIMESTAMP(6) NOT NULL,
    plan_id VARCHAR(128),
    synthesis_id VARCHAR(128),
    assistant_message_id BIGINT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id, session_id, client_request_id),
    CONSTRAINT fk_agent_v2_delivery_synthesis FOREIGN KEY (synthesis_id)
        REFERENCES agent_v2_final_syntheses(synthesis_id)
);
