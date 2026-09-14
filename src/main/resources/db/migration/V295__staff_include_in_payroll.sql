-- Let tenants choose who appears on payroll runs (owners/admins/managers via staff.hr.update).
-- Existing staff stay included; turn the flag off to hide someone from pay runs without terminating them.

ALTER TABLE staff_profiles
  ADD COLUMN include_in_payroll BOOLEAN NOT NULL DEFAULT TRUE;
