ALTER TABLE agent_v2_receipts
    ADD CONSTRAINT uk_agent_v2_receipts_id_tool_call
        UNIQUE (receipt_id, tool_call_id);

CREATE TABLE agent_v2_effect_progress (
    effect_progress_id VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    sequence_number BIGINT NOT NULL,
    lease_owner_id VARCHAR(255) NOT NULL,
    fencing_token BIGINT NOT NULL,
    request_format_version INT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (effect_progress_id),
    UNIQUE KEY uk_agent_v2_effect_progress_stream
        (tool_call_id, sequence_number),
    CONSTRAINT ck_agent_v2_effect_progress_values
        CHECK (sequence_number > 0 AND fencing_token > 0),
    CONSTRAINT ck_agent_v2_effect_progress_formats
        CHECK (request_format_version = 1 AND result_format_version = 1),
    CONSTRAINT ck_agent_v2_effect_progress_hashes
        CHECK (request_sha256 REGEXP '^[0-9a-f]{64}$'
          AND result_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT fk_agent_v2_effect_progress_intent
        FOREIGN KEY (tool_call_id)
        REFERENCES agent_v2_effect_intents (tool_call_id)
);

CREATE TABLE agent_v2_effect_results (
    tool_call_id VARCHAR(128) NOT NULL,
    receipt_id VARCHAR(128) NOT NULL,
    lease_owner_id VARCHAR(255) NOT NULL,
    fencing_token BIGINT NOT NULL,
    request_format_version INT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    result_format_version INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tool_call_id),
    UNIQUE KEY uk_agent_v2_effect_results_receipt (receipt_id),
    CONSTRAINT ck_agent_v2_effect_results_values
        CHECK (fencing_token > 0),
    CONSTRAINT ck_agent_v2_effect_results_formats
        CHECK (request_format_version = 1 AND result_format_version = 1),
    CONSTRAINT ck_agent_v2_effect_results_hashes
        CHECK (request_sha256 REGEXP '^[0-9a-f]{64}$'
          AND result_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT fk_agent_v2_effect_results_intent
        FOREIGN KEY (tool_call_id)
        REFERENCES agent_v2_effect_intents (tool_call_id),
    CONSTRAINT fk_agent_v2_effect_results_receipt
        FOREIGN KEY (receipt_id, tool_call_id)
        REFERENCES agent_v2_receipts (receipt_id, tool_call_id)
);
