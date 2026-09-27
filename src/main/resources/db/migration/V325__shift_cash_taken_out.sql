-- Cash the cashier removes from the till when closing (owner drop / safe).
-- Distinct from mid-shift drawouts and from operating expenses.
ALTER TABLE shifts
  ADD COLUMN cash_taken_out DECIMAL(14, 2) NULL;
