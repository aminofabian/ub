-- Slice 3 — Auth sessions, password reset, API keys (PHASE_1_PLAN.md §3.1)
-- MySQL: no partial indexes; use plain indexes + application filters on revoked_at.

CREATE TABLE user_sessions (
  id                   CHAR(36) PRIMARY KEY,
  user_id              CHAR(36) NOT NULL,
  business_id          CHAR(36) NOT NULL,
  access_token_jti     CHAR(36) NOT NULL UNIQUE,
  refresh_token_hash   VARCHAR(64) NOT NULL UNIQUE,
  user_agent           VARCHAR(500) NULL,
  ip                   VARCHAR(45) NULL,
  issued_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at           TIMESTAMP NOT NULL,
  refresh_expires_at   TIMESTAMP NOT NULL,
  revoked_at           TIMESTAMP NULL,
  rotated_to           CHAR(36) NULL,
  CONSTRAINT fk_user_sessions_user     FOREIGN KEY (user_id)     REFERENCES users(id),
  CONSTRAINT fk_user_sessions_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_user_sessions_rotated  FOREIGN KEY (rotated_to) REFERENCES user_sessions(id)
);

CREATE INDEX idx_user_sessions_user ON user_sessions (user_id);
CREATE INDEX idx_user_sessions_business ON user_sessions (business_id);

CREATE TABLE password_reset_tokens (
  id          CHAR(36) PRIMARY KEY,
  user_id     CHAR(36) NOT NULL,
  token_hash  VARCHAR(64) NOT NULL UNIQUE,
  expires_at  TIMESTAMP NOT NULL,
  used_at     TIMESTAMP NULL,
  CONSTRAINT fk_pwd_reset_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_pwd_reset_user ON password_reset_tokens (user_id);

CREATE TABLE api_keys (
  id            CHAR(36) PRIMARY KEY,
  business_id   CHAR(36) NOT NULL,
  user_id       CHAR(36) NULL,
  label         VARCHAR(255) NOT NULL,
  token_hash    VARCHAR(64) NOT NULL UNIQUE,
  token_prefix  CHAR(8) NOT NULL,
  scopes        JSON NOT NULL,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  last_used_at  TIMESTAMP NULL,
  expires_at    TIMESTAMP NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_api_keys_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_api_keys_user     FOREIGN KEY (user_id)     REFERENCES users(id)
);

CREATE INDEX idx_api_keys_business ON api_keys (business_id);
