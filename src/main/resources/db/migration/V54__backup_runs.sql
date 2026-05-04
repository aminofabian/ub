-- Phase 8 Slice 4 — encrypted DB dump artefacts (metadata only; files live in S3 or local dir).

CREATE TABLE backup_runs (
  id                 CHAR(36) PRIMARY KEY,
  status             VARCHAR(32)  NOT NULL,
  engine             VARCHAR(16)  NOT NULL,
  storage_key        VARCHAR(1024) NULL,
  encrypted_bytes    BIGINT NULL,
  plaintext_bytes    BIGINT NULL,
  sha256_hex         VARCHAR(64)  NULL,
  error_message      VARCHAR(2000) NULL,
  started_at         TIMESTAMP(3) NOT NULL,
  finished_at        TIMESTAMP(3) NULL,
  created_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_backup_runs_started_at ON backup_runs (started_at DESC);
CREATE INDEX idx_backup_runs_status_started ON backup_runs (status, started_at DESC);
