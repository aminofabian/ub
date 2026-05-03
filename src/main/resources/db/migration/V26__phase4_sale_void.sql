-- Phase 4 Slice 4 — void sale audit + permissions (PHASE_4_PLAN.md §Slice 4, void path).

ALTER TABLE sales
  ADD COLUMN voided_at TIMESTAMP NULL,
  ADD COLUMN voided_by CHAR(36) NULL,
  ADD COLUMN void_journal_entry_id CHAR(36) NULL,
  ADD COLUMN void_notes VARCHAR(2000) NULL;

ALTER TABLE sales
  ADD CONSTRAINT fk_sales_voided_by FOREIGN KEY (voided_by) REFERENCES users (id),
  ADD CONSTRAINT fk_sales_void_je FOREIGN KEY (void_journal_entry_id) REFERENCES journal_entries (id);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000068', 'sales.void.own',
   'Void own completed sales while the same shift is still open.'),
  ('11111111-0000-0000-0000-000000000069', 'sales.void.any',
   'Void any completed sale while its shift is still open.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000069'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000069'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000068'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000069'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000068'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000069');
