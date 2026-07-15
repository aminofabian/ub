-- Allow owner / admin / manager to correct mis-recorded sale tender
-- (e.g. M-Pesa posted as cash or credit) without voiding the sale.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000075', 'sales.payment.adjust',
   'Adjust payment method(s) on a completed sale (cash / M-Pesa / card / credit).');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'sales.payment.adjust'
  AND r.role_key IN ('owner', 'admin', 'manager')
  AND NOT EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
  );
