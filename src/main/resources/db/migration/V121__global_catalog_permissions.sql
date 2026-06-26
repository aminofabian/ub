-- Permissions for browsing and adopting from the global product catalog.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000400', 'catalog.global.read',  'Browse the global product catalog templates.'),
  ('11111111-0000-0000-0000-000000000401', 'catalog.global.adopt', 'Import products from the global catalog into the business catalog.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key IN ('catalog.global.read', 'catalog.global.adopt')
  AND r.role_key IN ('owner', 'admin', 'manager', 'stock_manager');

-- Platform admin permissions (super-admin only, not granted to tenant roles).
INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000402', 'platform.global_catalog.read',  'View platform global catalog templates.'),
  ('11111111-0000-0000-0000-000000000403', 'platform.global_catalog.write', 'Create and edit platform global catalog templates.');
