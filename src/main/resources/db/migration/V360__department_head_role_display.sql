-- Rename the grocery_clerk display name to Department Head so the role
-- reads as department-agnostic (Butchery, Fresh Produce, Bakery, etc.).
-- role_key stays grocery_clerk for permission and API compatibility.

UPDATE roles
   SET name = 'Department Head',
       description = 'Runs a department counter: builds carts and forwards invoices to the cashier. Scoped to assigned departments. Cannot process sales or operate the till.'
 WHERE role_key = 'grocery_clerk'
   AND business_id IS NULL;
