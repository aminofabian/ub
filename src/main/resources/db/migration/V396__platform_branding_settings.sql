-- Super-admin-managed platform branding (logo shown in platform transactional
-- email like the onboarding sequence). Singleton row; blank = fall back to the
-- built-in "K" wordmark badge.

CREATE TABLE platform_branding_settings (
    id                   CHAR(36)      NOT NULL PRIMARY KEY,
    email_logo_url       VARCHAR(1024) NULL,
    email_logo_public_id VARCHAR(512)  NULL,
    updated_at           TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_branding_settings (id)
VALUES ('00000000-0000-0000-0000-000000000001');
