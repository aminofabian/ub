-- Links a system-posted finance expense back to the row that produced it
-- (today: an approved cash drawout). Keeps approval-time posting idempotent
-- and lets a void reverse the exact expense it created.
ALTER TABLE expenses
  ADD COLUMN source_reference VARCHAR(36) NULL;

-- MySQL/MariaDB allow many NULLs in a unique index, so manual / recurring /
-- payroll rows (source_reference IS NULL) are unaffected.
CREATE UNIQUE INDEX ux_expenses_source_reference
  ON expenses (business_id, source, source_reference);
