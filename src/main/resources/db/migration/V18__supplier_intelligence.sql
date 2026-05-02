-- Phase 2 Slice 5 — supplier intelligence read API (PHASE_2_PLAN.md §Slice 5).

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000053', 'purchasing.intelligence.read',
   'View supplier spend, price competitiveness, and single-source risk reports.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000053'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000053'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000053');

CREATE INDEX idx_si_business_invoice_date ON supplier_invoices (business_id, invoice_date, status);
