CREATE TABLE demo_chat_archive_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_session_id BIGINT NOT NULL,
    title VARCHAR(255) NULL,
    scope VARCHAR(32) NOT NULL,
    source_project_id BIGINT NULL,
    model_provider_snapshot VARCHAR(64) NOT NULL,
    model_snapshot VARCHAR(128) NOT NULL,
    session_created_at TIMESTAMP(6) NOT NULL,
    session_updated_at TIMESTAMP(6) NOT NULL,
    archived_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_demo_chat_archive_source_session UNIQUE (source_session_id),
    CONSTRAINT fk_demo_chat_archive_user FOREIGN KEY (user_id) REFERENCES sys_users(id),
    INDEX idx_demo_chat_archive_user_updated (user_id, session_updated_at)
);

CREATE TABLE demo_chat_archive_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    archive_session_id BIGINT NOT NULL,
    source_message_id BIGINT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NULL,
    message_created_at TIMESTAMP(6) NOT NULL,
    deletable BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_demo_chat_archive_message_session
        FOREIGN KEY (archive_session_id) REFERENCES demo_chat_archive_sessions(id) ON DELETE CASCADE,
    INDEX idx_demo_chat_archive_message_order (archive_session_id, message_created_at, id)
);
