-- Phase 8 Slice 3 — async CSV import jobs + staged payloads.

CREATE TABLE import_jobs (
  id                     CHAR(36) PRIMARY KEY,
  business_id            CHAR(36) NOT NULL,
  kind                   VARCHAR(32) NOT NULL,
  status                 VARCHAR(32) NOT NULL,
  dry_run                BOOLEAN NOT NULL,
  actor_user_id          CHAR(36) NOT NULL,
  original_filename      VARCHAR(500) NULL,
  payload_relative_path  VARCHAR(1024) NOT NULL,
  rows_total             INT NULL,
  rows_processed         INT NOT NULL DEFAULT 0,
  rows_committed         INT NULL,
  errors_json            JSON NULL,
  status_message         VARCHAR(1000) NULL,
  created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_at           TIMESTAMP NULL,
  CONSTRAINT fk_import_jobs_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_import_jobs_status_created ON import_jobs (status, created_at);
CREATE INDEX idx_import_jobs_business_created ON import_jobs (business_id, created_at);
