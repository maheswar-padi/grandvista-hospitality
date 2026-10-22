-- auth-service owns all identity data. No other service reads these tables.

CREATE TABLE users (
    id                    UUID PRIMARY KEY,
    email                 VARCHAR(254) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(120) NOT NULL,
    phone                 VARCHAR(32),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    -- e-mail addresses are normalised to lower case by the application before storage
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ux_users_email UNIQUE (email)
);

CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(20) NOT NULL CHECK (role IN ('CUSTOMER', 'STAFF', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

-- Refresh tokens are stored only as SHA-256 hashes. Tokens rotate on every use; all tokens issued
-- from one login share a family so that replay of a rotated token revokes the whole family.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL CHECK (length(token_hash) = 64),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_expiry ON refresh_tokens (expires_at);

-- Security-relevant events (no passwords, no tokens).
CREATE TABLE auth_audit_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID,
    event_type  VARCHAR(40)  NOT NULL,
    detail      VARCHAR(255),
    occurred_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_auth_audit_user ON auth_audit_events (user_id, occurred_at DESC);
