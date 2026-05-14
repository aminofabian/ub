-- V76: Add stock_manager system role with stock-take and inventory permissions.

INSERT INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-000000000007', NULL, 'stock_manager', 'Stock Manager',
   'Can run stock-takes, view inventory, and reconcile sessions. No catalog write or user management.',
   TRUE);

-- stock_manager permissions:
--   inventory.read     — view batches, valuation, stock movements
--   inventory.transfer — create/complete stock transfers
--   stocktake.read     — view sessions and adjustment requests
--   stocktake.run      — start sessions, enter counts, close
--   stocktake.approve  — approve/reject adjustment requests
--   catalog.items.read — read item names/skus (needed to resolve names in stock-take)
INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000054'), -- inventory.read
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000056'), -- inventory.transfer
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000057'), -- stocktake.read
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000058'), -- stocktake.run
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000059'), -- stocktake.approve
  ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000040'); -- catalog.items.read
