-- V210: Grocery clerk stock-in (Path B) — list suppliers + read sessions.
-- Write stays delegated via inventory.receiveStock.allowReceiveForGroceryClerk
-- (see InventoryRoleAccessService.grantsDelegatedPathBWrite).

INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000008', p.id
  FROM permissions p
 WHERE p.permission_key IN (
   'suppliers.read',
   'purchasing.path_b.read'
 )
   AND NOT EXISTS (
     SELECT 1
       FROM role_permissions rp
      WHERE rp.role_id = '22222222-0000-0000-0000-000000000008'
        AND rp.permission_id = p.id
   );
