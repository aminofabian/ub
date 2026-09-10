-- Platform signup / email-verification policy (super-admin overridable).
-- Default matches app.auth.email-verification-required=true.

CREATE TABLE platform_auth_settings (
    id                              CHAR(36)     NOT NULL PRIMARY KEY,
    email_verification_required     TINYINT(1)   NOT NULL DEFAULT 1,
    updated_at                      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_auth_settings (id, email_verification_required)
VALUES ('00000000-0000-0000-0000-000000000001', 1);

ALTER TABLE email_verification_tokens
    ADD COLUMN otp_hash CHAR(64) NULL;

CREATE INDEX idx_email_verif_otp_unused
    ON email_verification_tokens (otp_hash, used_at);
