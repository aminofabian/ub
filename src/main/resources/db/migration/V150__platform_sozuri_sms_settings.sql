-- Platform-wide Sozuri SMS settings (admin-configurable; no env required).

ALTER TABLE platform_integration_settings
  ADD COLUMN sms_provider VARCHAR(32) NULL,
  ADD COLUMN sozuri_project VARCHAR(128) NULL,
  ADD COLUMN sozuri_api_key_enc TEXT NULL,
  ADD COLUMN sozuri_from VARCHAR(64) NULL,
  ADD COLUMN sozuri_type VARCHAR(32) NULL,
  ADD COLUMN sozuri_api_url VARCHAR(512) NULL;
