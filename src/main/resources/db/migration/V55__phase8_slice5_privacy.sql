-- Phase 8 Slice 5 — GDPR/DPA-style subject export + customer anonymisation (implement.md §1172).

CREATE TABLE privacy_export_jobs (
  id               CHAR(36)      PRIMARY KEY,
  business_id      CHAR(36)      NOT NULL,
  subject_type     VARCHAR(16)   NOT NULL,
  subject_id       CHAR(36)      NOT NULL,
  status           VARCHAR(16)   NOT NULL,
  storage_path     VARCHAR(1024) NULL,
  download_token   CHAR(36)      NULL,
  expires_at       TIMESTAMP(3)  NULL,
  error_message    VARCHAR(2000) NULL,
  created_by       CHAR(36)      NOT NULL,
  created_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_privacy_export_jobs_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE INDEX idx_privacy_export_jobs_business_created ON privacy_export_jobs (business_id, created_at);

ALTER TABLE customers
  ADD COLUMN anonymised_at TIMESTAMP(3) NULL;

INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000108', 'integrations.privacy.manage',
   'Export data subjects (customers, staff) and run customer PII anonymisation for GDPR/DPA.');

INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000108'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000108');
