-- V126: Weighed refund permission.
--
-- Weighed items (kg/g/lb) can be refunded, but the returned meat is treated as
-- wastage for food-safety reasons. This permission gates that flow separately
-- from the generic `sales.refund.create` permission that cashiers hold.
--
-- Granted by default to owner, admin, and manager.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000074', 'sales.weighed.refund',
   'Refund weighed sale lines; returned stock is written off as wastage.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000074'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000074'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000074');
