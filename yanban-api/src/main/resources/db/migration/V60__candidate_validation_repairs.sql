CREATE TABLE candidate_validation_repairs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_validation_id VARCHAR(36) NOT NULL,
    source_candidate_artifact_id BIGINT NOT NULL,
    source_candidate_fingerprint VARCHAR(64) NOT NULL,
    selected_change_index INT NOT NULL,
    selected_path VARCHAR(512) NOT NULL,
    failed_receipt_digest VARCHAR(64) NOT NULL,
    project_version VARCHAR(64) NOT NULL,
    attempt INT NOT NULL,
    max_attempts INT NOT NULL,
    source_replacement_text LONGTEXT NOT NULL,
    source_replacement_digest VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    repaired_artifact_id BIGINT NULL,
    repaired_validation_id VARCHAR(36) NULL,
    dependency_coordinates_json TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_candidate_validation_repair_source (source_validation_id)
);

ALTER TABLE candidate_sandbox_validations
    ADD COLUMN repair_origin_validation_id VARCHAR(36) NULL,
    ADD COLUMN repair_attempt INT NULL,
    ADD COLUMN dependency_coordinates_json TEXT NULL;

ALTER TABLE agent_v2_project_candidate_deliveries
    ADD COLUMN repair_source_validation_id VARCHAR(36) NULL,
    ADD COLUMN repair_source_artifact_id BIGINT NULL,
    ADD COLUMN repair_source_fingerprint VARCHAR(64) NULL,
    ADD COLUMN repair_selected_index INT NULL,
    ADD COLUMN repair_selected_path VARCHAR(512) NULL,
    ADD COLUMN repair_failed_receipt_digest VARCHAR(64) NULL,
    ADD COLUMN repair_attempt INT NULL,
    ADD COLUMN repair_max_attempts INT NULL,
    ADD COLUMN repair_source_replacements_json LONGTEXT NULL,
    ADD COLUMN repair_source_replacements_sha256 VARCHAR(64) NULL,
    ADD COLUMN repair_diagnostic TEXT NULL,
    ADD COLUMN prepared_maven_coordinates_json TEXT NULL,
    ADD COLUMN prepared_maven_coordinates_sha256 VARCHAR(64) NULL;
