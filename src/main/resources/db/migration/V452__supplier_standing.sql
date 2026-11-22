-- Supplier standing (SUPPLIER_ENGAGEMENT_DESIGN.md §4.2, D5): the supplier's own dispatch lead time, in
-- working days. Two by default; the supplier can change it.
ALTER TABLE marketplace_suppliers
  ADD COLUMN dispatch_lead_working_days INT NOT NULL DEFAULT 2;
