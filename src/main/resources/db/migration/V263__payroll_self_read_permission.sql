-- Staff self-service: view own salary, advances, and payslips (not shop-wide payroll).

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000606', 'payroll.self.read',
   'View own salary, advance balance, and payslip history.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'payroll.self.read'
  AND r.business_id IS NULL
  AND r.role_key IN (
    'owner', 'admin', 'manager', 'cashier', 'viewer',
    'stock_manager', 'grocery_clerk', 'butcher_cashier'
  )
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
