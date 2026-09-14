CREATE TABLE user_installed_skills (
    id VARCHAR(48) NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    name_key VARCHAR(100) NOT NULL,
    description VARCHAR(1024) NOT NULL,
    prompt LONGTEXT NOT NULL,
    metadata LONGTEXT NULL,
    allowed_tools_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_user_installed_skills_name UNIQUE (user_id, name_key),
    CONSTRAINT fk_user_installed_skills_owner FOREIGN KEY (user_id) REFERENCES sys_users (id)
);
