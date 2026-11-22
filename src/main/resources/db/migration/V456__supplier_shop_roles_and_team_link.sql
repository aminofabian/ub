-- P1-3 (part): role presets for a supplier's shop, and the link from a supplier portal user to the shop user
-- invited for them. Nobody's sign-in changes: a portal user is invited to the shop the way any staff member is.
ALTER TABLE supplier_users
    ADD business_user_id VARCHAR(36) NULL;

INSERT INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-000000000011', NULL, 'supplier_trade_desk', 'Trade desk',
   'Answers and dispatches trade orders in a supplier''s shop. Reads products, prices and buyer accounts.',
   TRUE),
  ('22222222-0000-0000-0000-000000000012', NULL, 'supplier_catalogue', 'Catalogue',
   'Keeps a supplier''s products, sections and selling prices. Does not handle orders or accounts.',
   TRUE),
  ('22222222-0000-0000-0000-000000000013', NULL, 'supplier_accounts', 'Accounts',
   'Decides buyer accounts and records what buyers pay in a supplier''s shop. Reads trade orders.',
   TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000011', p.id
  FROM permissions p
 WHERE p.permission_key IN (
   'storefront.orders.read',
   'catalog.items.read',
   'pricing.read',
   'credits.customers.read'
 );

INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000012', p.id
  FROM permissions p
 WHERE p.permission_key IN (
   'catalog.items.read',
   'catalog.items.write',
   'catalog.categories.write',
   'pricing.read',
   'pricing.sell_price.set'
 );

INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000013', p.id
  FROM permissions p
 WHERE p.permission_key IN (
   'storefront.orders.read',
   'credits.customers.read',
   'credits.customers.write',
   'catalog.items.read',
   'pricing.read'
 );
