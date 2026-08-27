ALTER TABLE sys_users
    ADD COLUMN deleted_at TIMESTAMP(6) NULL;

CREATE INDEX idx_sys_users_active_created
    ON sys_users (deleted_at, created_at);

ALTER TABLE invite_codes
    ADD COLUMN deleted_at TIMESTAMP(6) NULL;

CREATE INDEX idx_invite_codes_active_created
    ON invite_codes (deleted_at, created_at);
