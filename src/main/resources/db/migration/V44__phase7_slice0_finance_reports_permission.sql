-- Phase 7 Slice 0 — Finance reports permission for the pulse / P&L / balance-sheet read endpoints
-- shipped as the Phase 6 close-out gate (PHASE_7_PLAN.md §Slice 0 / PHASE_6_PLAN.md §Slices 4-6).
-- Pure read aggregation over journal_lines + ledger_accounts + expenses + sales; no schema changes.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000100', 'finance.reports.read',
   'View finance reports (owner pulse, simple P&L, simple balance sheet, daily cash summary).');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000100'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000100'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000100'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000100');
