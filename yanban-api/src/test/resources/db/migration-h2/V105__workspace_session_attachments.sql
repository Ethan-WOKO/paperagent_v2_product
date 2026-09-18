CREATE TABLE agent_session_attachments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 session_id BIGINT NOT NULL,
 filename VARCHAR(255) NOT NULL,
 mime_type VARCHAR(128) NOT NULL,
 object_key VARCHAR(512) NOT NULL,
 file_size BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,
 extracted_text LONGTEXT,
 error_message VARCHAR(512),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 first_message_id BIGINT,
 knowledge_document_id BIGINT,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_session_attachment_session FOREIGN KEY (session_id) REFERENCES agent_sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_session_attachment_owner ON agent_session_attachments(user_id,session_id,active);

CREATE TABLE agent_attachment_snapshots (
 id VARCHAR(512) PRIMARY KEY,
 session_id BIGINT NOT NULL,
 attachment_ids VARCHAR(256) NOT NULL,
 CONSTRAINT fk_attachment_snapshot_session FOREIGN KEY (session_id) REFERENCES agent_sessions(id) ON DELETE CASCADE
);
