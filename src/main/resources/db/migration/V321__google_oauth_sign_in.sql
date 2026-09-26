-- Google Sign-In for merchant owners (platform OAuth client + identity link + PKCE state).

ALTER TABLE platform_integration_settings
  ADD COLUMN google_oauth_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN google_oauth_client_id VARCHAR(255) NULL,
  ADD COLUMN google_oauth_client_secret_enc TEXT NULL;

CREATE TABLE user_oauth_identities (
    id                CHAR(36)     NOT NULL,
    business_id       CHAR(36)     NOT NULL,
    user_id           CHAR(36)     NOT NULL,
    provider          VARCHAR(32)  NOT NULL,
    provider_subject  VARCHAR(255) NOT NULL,
    email_at_link     VARCHAR(191) NULL,
    created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_uoi_provider_subject_business (provider, provider_subject, business_id),
    UNIQUE KEY uq_uoi_user_provider (user_id, provider),
    KEY idx_uoi_business (business_id),
    KEY idx_uoi_user (user_id)
);

CREATE TABLE oauth_login_states (
    state_hash        VARCHAR(64)  NOT NULL,
    code_verifier     VARCHAR(128) NOT NULL,
    intent            VARCHAR(32)  NOT NULL,
    next_path         VARCHAR(512) NULL,
    business_id       CHAR(36)     NULL,
    onboard_draft_json TEXT        NULL,
    browser_binding   VARCHAR(64)  NOT NULL,
    nonce             VARCHAR(64)  NOT NULL,
    redirect_uri      VARCHAR(512) NOT NULL,
    expires_at        TIMESTAMP(3) NOT NULL,
    consumed_at       TIMESTAMP(3) NULL,
    PRIMARY KEY (state_hash),
    KEY idx_ols_expires_at (expires_at)
);
