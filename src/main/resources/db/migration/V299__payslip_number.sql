-- Human-friendly payslip references, e.g. PAL-2026-09-0042.
-- Nullable: payslips issued before numbering stay NULL and print without a number.

ALTER TABLE payslips
  ADD COLUMN payslip_number VARCHAR(40) NULL;

CREATE UNIQUE INDEX uq_payslips_number ON payslips (payslip_number);
