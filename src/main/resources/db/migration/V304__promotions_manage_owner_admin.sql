-- V104 seeded notifications.promotions.manage but only linked it to the system
-- owner role (business_id IS NULL). Admins never received it, and any
-- tenant-scoped owner/admin roles were skipped. Owners and admins should both
-- manage promo push campaigns.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'notifications.promotions.manage'
  AND r.role_key IN ('owner', 'admin')
  AND NOT EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
  );
