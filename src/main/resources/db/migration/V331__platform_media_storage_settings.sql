-- Super-admin managed media storage: which provider new uploads go to, and the Cloudflare R2 bucket.
-- Singleton row; secrets are encrypted with the payments credential key like other platform settings.

CREATE TABLE platform_media_storage_settings (
    id                      CHAR(36)     NOT NULL,
    upload_provider         VARCHAR(16)  NOT NULL DEFAULT 'cloudinary',
    r2_account_id           VARCHAR(64)  NULL,
    r2_endpoint             VARCHAR(512) NULL,
    r2_bucket               VARCHAR(128) NULL,
    r2_access_key_id_enc    TEXT         NULL,
    r2_secret_access_key_enc TEXT        NULL,
    r2_public_base_url      VARCHAR(512) NULL,
    updated_at              TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
);
