-- V135: Grant grocery invoice permissions to the admin role.
--
-- V109 granted grocery.invoices.* to owner, manager, cashier, and stock_manager
-- but omitted admin. Admin is intended to have full tenant access (except
-- subscription management), matching owner for operational workflows.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.role_key = 'admin'
  AND p.permission_key IN (
      'grocery.invoices.create',
      'grocery.invoices.read',
      'grocery.invoices.cancel',
      'grocery.invoices.pay'
  )
  AND NOT EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
  );
