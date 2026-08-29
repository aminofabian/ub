-- Payroll statutory breakdown + expense payment method on payslips.

ALTER TABLE payslips
  ADD COLUMN paye_deducted DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER other_deductions,
  ADD COLUMN nssf_deducted DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER paye_deducted,
  ADD COLUMN shif_deducted DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER nssf_deducted,
  ADD COLUMN housing_levy_deducted DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER shif_deducted,
  ADD COLUMN payment_method VARCHAR(32) NULL AFTER expense_id;
