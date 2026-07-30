ALTER TABLE agent_v2_project_candidate_deliveries
    ADD COLUMN prepared_replacements_json LONGTEXT NULL AFTER workspace_id,
    ADD COLUMN prepared_replacements_sha256 VARCHAR(64) NULL
        AFTER prepared_replacements_json,
    ADD COLUMN prepared_diff_fingerprint VARCHAR(64) NULL
        AFTER prepared_replacements_sha256;
