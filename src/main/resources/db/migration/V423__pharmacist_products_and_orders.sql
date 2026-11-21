-- Pharmacy — the Pharmacist role can add medicines and raise orders (ADR 0033).
--
-- The role was built from the Cashier's permissions plus the pharmacy keys (V415), then given
-- stock permissions (V421). It still could not do two things the job needs:
--
--  * Add a medicine. A pharmacist could mark an existing product as a medicine but not create
--    one, by hand or from the shared pharmacy catalogue, and could not give it a selling price.
--  * Raise an order. A pharmacist could see what to order (Pharmacy, Ordering) but not create the
--    draft purchase order, which needs purchasing.path_a.write and a supplier to choose.
--
-- Adds to the Pharmacist system role only:
--   catalog.items.write      create and edit products
--   pricing.sell_price.set   set a selling price (not cost prices, price rules or discounts)
--   catalog.global.read      browse the shared catalogue
--   catalog.global.adopt     add products from it
--   purchasing.path_a.read   see purchase orders
--   purchasing.path_a.write  create and edit them
--   suppliers.read           choose a supplier
--
-- Not added: staff, business settings, reports, cost prices, pricing rules, supplier editing.
-- Idempotent (INSERT IGNORE). No DDL. A key that does not exist on a database is simply skipped.

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-00000000000d', p.id
  FROM permissions p
 WHERE p.permission_key IN (
   'catalog.items.write',
   'pricing.sell_price.set',
   'catalog.global.read',
   'catalog.global.adopt',
   'purchasing.path_a.read',
   'purchasing.path_a.write',
   'suppliers.read'
 );
