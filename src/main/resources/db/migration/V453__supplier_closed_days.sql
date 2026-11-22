-- Supplier closed days (SUPPLIER_ENGAGEMENT_DESIGN.md §4.2, D4): dates the supplier marks as closed, as a JSON
-- array of ISO dates. A closed day stops the working clock; it counts against nobody.
ALTER TABLE marketplace_suppliers
  ADD COLUMN closed_dates_json TEXT NULL;
