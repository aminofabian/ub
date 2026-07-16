-- V144: Receive stock for cashier / stock_manager becomes an admin business setting.
-- Remove hardcoded purchasing.path_b.write grants; settings default to enabled
-- (absent → true) so existing shops keep current behaviour until an admin turns it off.
-- Keep purchasing.path_b.read so they can still view supply history.

-- Cashier (004)
DELETE FROM role_permissions
 WHERE role_id = '22222222-0000-0000-0000-000000000004'
   AND permission_id = '11111111-0000-0000-0000-000000000048';

-- Stock manager (007)
DELETE FROM role_permissions
 WHERE role_id = '22222222-0000-0000-0000-000000000007'
   AND permission_id = '11111111-0000-0000-0000-000000000048';

-- Butcher cashier (009) — mirrored cashier grants
DELETE FROM role_permissions
 WHERE role_id = '22222222-0000-0000-0000-000000000009'
   AND permission_id = '11111111-0000-0000-0000-000000000048';
