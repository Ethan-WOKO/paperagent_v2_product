CREATE TABLE agent_v2_plan_leases (
    plan_id VARCHAR(128) NOT NULL,
    fencing_token BIGINT NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    lease_token VARCHAR(255) NOT NULL,
    acquired_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    released_at TIMESTAMP(6) NULL,
    PRIMARY KEY (plan_id, fencing_token),
    CONSTRAINT uk_agent_v2_plan_leases_token UNIQUE (lease_token),
    CONSTRAINT fk_agent_v2_plan_leases_bootstrap
        FOREIGN KEY (plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id),
    CONSTRAINT ck_agent_v2_plan_leases_fence CHECK (fencing_token > 0),
    CONSTRAINT ck_agent_v2_plan_leases_owner
        CHECK (CHAR_LENGTH(TRIM(owner_id)) > 0),
    CONSTRAINT ck_agent_v2_plan_leases_token
        CHECK (CHAR_LENGTH(TRIM(lease_token)) > 0),
    CONSTRAINT ck_agent_v2_plan_leases_expiry CHECK (expires_at > acquired_at)
);
