-- V79: Add stocktake.delete permission — granted ONLY to admin (not owner, not stock_manager).
-- This ensures only admins can delete stock-take sessions.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000109', 'stocktake.delete',
   'Delete stock-take sessions.');

-- Grant to admin role only (not owner, not manager, not stock_manager).
INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000109');
