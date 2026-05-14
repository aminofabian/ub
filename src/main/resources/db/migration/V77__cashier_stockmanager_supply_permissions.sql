-- V77: Allow cashier and stock_manager to create supplies (Path B purchasing).
-- They need:
--   - purchasing.path_b.read  — to view the supplies list
--   - purchasing.path_b.write — to create sessions, add lines, and post
--   - suppliers.read          — stock_manager needs this (cashier already has it)

INSERT INTO role_permissions (role_id, permission_id) VALUES
  -- Cashier: path_b read + write
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000047'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000048'),
  -- Stock manager: path_b read + write + suppliers.read
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000044'),
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000047'),
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000048');
