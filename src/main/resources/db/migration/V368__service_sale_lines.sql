-- Service sale lines and tips (ADR 0014 + its 2026-11-13 amendment, tickets T5/T6).
--
-- Existing tables are extended instead of adding new ones; every column is nullable or
-- defaulted, so retail rows are untouched. V215, V353 and V359 extended sale_items the same way.
-- ADD COLUMN at the end of the table is a metadata-only change on MySQL 8 and MariaDB 10.11.
-- No foreign keys or indexes are added here: both would rebuild or scan sale_items, the
-- highest-volume table. Service and performer ids are validated by the application when the
-- sale is posted, and staff statements read the staff ledger, not sale_items.

ALTER TABLE sale_items
  ADD COLUMN service_id CHAR(36) NULL,
  ADD COLUMN performer_staff_profile_id CHAR(36) NULL,
  ADD COLUMN service_minutes INT NULL;

-- Tips taken with the sale on behalf of staff. Not part of grand_total (a tip is never
-- revenue); the sale's payments cover grand_total + tip_total.
ALTER TABLE sales
  ADD COLUMN tip_total DECIMAL(14,2) NOT NULL DEFAULT 0;
