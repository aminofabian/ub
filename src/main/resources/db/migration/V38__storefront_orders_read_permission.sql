-- Dashboard read access for Phase 16 web pickup orders.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000077', 'storefront.orders.read',
   'View online storefront pickup orders submitted by guests.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000077'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000077'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000077');
