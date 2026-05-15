-- Grant stocktake.delete to owner (V79 only granted admin).
-- Owners and admins should both be able to remove broken stock-take sessions.

INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000109'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000109');
