CREATE TABLE agent_v2_tool_call_claims (
    tool_call_id VARCHAR(128) NOT NULL,
    owner_kind VARCHAR(32) NOT NULL,
    PRIMARY KEY (tool_call_id),
    UNIQUE KEY uk_agent_v2_tool_call_claim_owner (tool_call_id, owner_kind),
    CONSTRAINT ck_agent_v2_tool_call_claim_owner
        CHECK (owner_kind IN ('EFFECT_INTENT', 'ORDINARY_RECEIPT'))
);

INSERT INTO agent_v2_tool_call_claims (tool_call_id, owner_kind)
SELECT tool_call_id, 'EFFECT_INTENT'
  FROM agent_v2_effect_intents
 ORDER BY tool_call_id;

ALTER TABLE agent_v2_effect_intents
    ADD COLUMN tool_call_owner_kind VARCHAR(32)
        NOT NULL DEFAULT 'EFFECT_INTENT';

ALTER TABLE agent_v2_effect_intents
    ADD CONSTRAINT ck_agent_v2_effect_intents_owner_kind
        CHECK (tool_call_owner_kind = 'EFFECT_INTENT');

ALTER TABLE agent_v2_effect_intents
    ADD CONSTRAINT fk_agent_v2_effect_intents_tool_call_claim
        FOREIGN KEY (tool_call_id, tool_call_owner_kind)
        REFERENCES agent_v2_tool_call_claims (tool_call_id, owner_kind);

CREATE TABLE agent_v2_receipts (
    receipt_id VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_call_claim_owner_kind VARCHAR(32) NOT NULL,
    receipt_owner_kind VARCHAR(32) NOT NULL,
    payload_format_version INT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (receipt_id),
    KEY idx_agent_v2_receipts_tool_call (tool_call_id),
    CONSTRAINT ck_agent_v2_receipts_ids
        CHECK (CHAR_LENGTH(TRIM(receipt_id)) > 0
          AND CHAR_LENGTH(TRIM(tool_call_id)) > 0),
    CONSTRAINT ck_agent_v2_receipts_owner_pair
        CHECK ((tool_call_claim_owner_kind = 'ORDINARY_RECEIPT'
                AND receipt_owner_kind = 'ORDINARY_RECEIPT')
            OR (tool_call_claim_owner_kind = 'EFFECT_INTENT'
                AND receipt_owner_kind = 'EFFECT_OUTCOME')),
    CONSTRAINT ck_agent_v2_receipts_format
        CHECK (payload_format_version = 1),
    CONSTRAINT ck_agent_v2_receipts_hash
        CHECK (payload_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT fk_agent_v2_receipts_tool_call_claim
        FOREIGN KEY (tool_call_id, tool_call_claim_owner_kind)
        REFERENCES agent_v2_tool_call_claims (tool_call_id, owner_kind)
);
