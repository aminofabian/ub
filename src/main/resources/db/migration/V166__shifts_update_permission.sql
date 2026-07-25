-- Allow owner / admin to correct opening float on an open shift.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000092', 'shifts.update',
   'Edit opening float and denominations on an open shift.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'shifts.update'
  AND r.role_key IN ('owner', 'admin')
  AND NOT EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
  );
