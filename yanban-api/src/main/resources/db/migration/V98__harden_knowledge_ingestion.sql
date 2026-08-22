ALTER TABLE kb_documents ADD COLUMN upload_id VARCHAR(64) NULL;
ALTER TABLE kb_documents ADD COLUMN file_digest VARCHAR(64) NULL;
ALTER TABLE kb_documents ADD COLUMN processing_event_id VARCHAR(64) NULL;
ALTER TABLE kb_documents ADD COLUMN processing_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE kb_documents ADD COLUMN processed_at TIMESTAMP NULL;

CREATE UNIQUE INDEX uk_kb_documents_user_upload ON kb_documents (user_id, upload_id);
CREATE INDEX idx_kb_documents_processing_status ON kb_documents (status, updated_at);

ALTER TABLE kb_chunk_uploads ADD COLUMN chunk_digest VARCHAR(64) NULL;
ALTER TABLE kb_chunks ADD COLUMN content_digest VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_kb_chunks_document_index ON kb_chunks (document_id, chunk_index);

CREATE TABLE kb_processing_outbox (
    event_id VARCHAR(64) NOT NULL,
    document_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_key VARCHAR(128) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at TIMESTAMP NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id),
    INDEX idx_kb_outbox_due (status, next_attempt_at),
    INDEX idx_kb_outbox_document (document_id, status),
    CONSTRAINT fk_kb_outbox_document FOREIGN KEY (document_id) REFERENCES kb_documents (id) ON DELETE CASCADE
);

CREATE TABLE kb_processing_dead_letters (
    id BIGINT NOT NULL AUTO_INCREMENT,
    original_event_id VARCHAR(64) NULL,
    document_id BIGINT NULL,
    message_key VARCHAR(128) NULL,
    payload_json LONGTEXT NOT NULL,
    error_type VARCHAR(255) NULL,
    error_message VARCHAR(512) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    redrive_event_id VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    redriven_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_dead_letter_event (original_event_id),
    INDEX idx_kb_dead_letter_status_created (status, created_at)
);
