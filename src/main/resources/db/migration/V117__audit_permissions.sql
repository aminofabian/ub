-- Permission to view the unified audit log / activity history.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000300', 'audit.read', 'View audit logs and activity history.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'audit.read'
  AND r.role_key IN ('owner', 'admin', 'manager');
