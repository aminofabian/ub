-- Desktop install log reporting (Super Admin → Platform → Logs).
--
-- Each row is one log bundle (gzip) shipped from a Kiosk Desktop install
-- when the machine happens to be online. The payload itself lives in S3 (or
-- the ingest local dir for dev); this table is the index + metadata.

CREATE TABLE desktop_log_uploads (
  id          CHAR(36) PRIMARY KEY,
  install_id  VARCHAR(64)  NOT NULL,
  business_id CHAR(36)     NULL,
  app_version VARCHAR(64)  NULL,
  file_key    VARCHAR(512) NOT NULL,
  filename    VARCHAR(255) NOT NULL,
  size_bytes  BIGINT       NOT NULL,
  uploaded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_desktop_log_uploads_install (install_id, uploaded_at),
  INDEX idx_desktop_log_uploads_uploaded (uploaded_at)
);
