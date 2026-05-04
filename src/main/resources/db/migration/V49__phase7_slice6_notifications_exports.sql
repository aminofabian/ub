-- Phase 7 Slice 6 — notifications inbox + export jobs (implement.md §5.10 + PHASE_7_PLAN.md §Slice 6).

CREATE TABLE notifications (
  id             CHAR(36)       PRIMARY KEY,
  business_id    CHAR(36)       NOT NULL,
  user_id        CHAR(36)       NULL,
  type           VARCHAR(64)    NOT NULL,
  dedupe_key     VARCHAR(191)   NULL,
  payload_json   TEXT           NOT NULL,
  read_at        TIMESTAMP(3)   NULL,
  created_at     TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_notifications_business_dedupe (business_id, dedupe_key)
);

CREATE INDEX idx_notifications_business_created ON notifications (business_id, created_at);

CREATE TABLE export_jobs (
  id                   CHAR(36)       PRIMARY KEY,
  business_id          CHAR(36)       NOT NULL,
  report_key           VARCHAR(64)    NOT NULL,
  format               VARCHAR(16)    NOT NULL,
  status               VARCHAR(16)    NOT NULL,
  params_json          TEXT           NULL,
  storage_path         VARCHAR(1024)  NULL,
  download_token       CHAR(36)       NULL,
  expires_at           TIMESTAMP(3)   NULL,
  error_message        VARCHAR(2000)  NULL,
  idempotency_key_hash VARCHAR(64)    NULL,
  created_by           CHAR(36)       NOT NULL,
  created_at           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_export_jobs_idem (business_id, idempotency_key_hash)
);

CREATE INDEX idx_export_jobs_business_created ON export_jobs (business_id, created_at);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000104', 'reports.notifications.read',
   'View in-app notifications.'),
  ('11111111-0000-0000-0000-000000000105', 'reports.notifications.write',
   'Mark notifications read.'),
  ('11111111-0000-0000-0000-000000000106', 'reports.export',
   'Queue async CSV/XLSX exports.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000104'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000105'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000106'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000104'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000105'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000106'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000104'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000105'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000106'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000104'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000105');
