-- V296: Stock managers run the full Path A buying flow —
-- create PO, send/confirm, mark arrived, and post GRN (receive).

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.role_key = 'stock_manager'
  AND p.permission_key IN (
    'purchasing.path_a.read',
    'purchasing.path_a.write'
  )
  AND NOT EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
  );

UPDATE roles
SET description = 'Can run stock-takes, place and receive purchase orders, walk-in supplies, and view inventory. No catalog write or user management.'
WHERE role_key = 'stock_manager'
  AND business_id IS NULL;
