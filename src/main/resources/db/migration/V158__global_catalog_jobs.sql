-- Phase 4 — async global catalog adopt / promote jobs (import_jobs pattern, separate kinds).

CREATE TABLE global_catalog_jobs (
  id              CHAR(36) PRIMARY KEY,
  kind            VARCHAR(32) NOT NULL,
  status          VARCHAR(32) NOT NULL,
  business_id     CHAR(36) NULL,
  actor_user_id   CHAR(36) NOT NULL,
  payload_json    JSON NOT NULL,
  result_json     JSON NULL,
  rows_total      INT NULL,
  rows_processed  INT NOT NULL DEFAULT 0,
  rows_committed  INT NULL,
  status_message  VARCHAR(1000) NULL,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_at    TIMESTAMP NULL,
  CONSTRAINT fk_global_catalog_jobs_business
    FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_global_catalog_jobs_status_created
  ON global_catalog_jobs (status, created_at);

CREATE INDEX idx_global_catalog_jobs_business_created
  ON global_catalog_jobs (business_id, created_at);
