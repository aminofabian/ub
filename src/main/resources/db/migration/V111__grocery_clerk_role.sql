-- V111: Grocery clerk role — counter staff who generate invoices for shoppers
-- but cannot process the payment (i.e. cannot create a sale or touch stock).
--
-- A grocery_clerk:
--   * Builds a grocery cart and emits a pending grocery invoice with a barcode.
--   * Can read / cancel only the invoices they themselves created.
--   * Cannot use the cashier / quick sale (no sales.sell, no grocery.invoices.pay).
--   * Locked to their assigned branch (see BranchResolutionService).
--
-- Per-user filtering of `grocery.invoices.read` and `grocery.invoices.cancel`
-- to "owned" rows is enforced in the service layer.

INSERT INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-000000000008', NULL, 'grocery_clerk', 'Grocery Clerk',
   'Generates grocery invoices at the counter. Can only view and cancel invoices they created. Cannot process sales or operate the cashier.',
   TRUE);

-- grocery_clerk permissions:
--   catalog.items.read       — browse / search the catalog while building the cart
--   pricing.read             — fetch shelf prices for tiles and modals
--   grocery.invoices.create  — generate a pending invoice
--   grocery.invoices.read    — list / view (filtered to own in the service layer)
--   grocery.invoices.cancel  — cancel only their own pending invoices
INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000008', p.id
  FROM permissions p
 WHERE p.permission_key IN (
   'catalog.items.read',
   'pricing.read',
   'grocery.invoices.create',
   'grocery.invoices.read',
   'grocery.invoices.cancel'
 );
