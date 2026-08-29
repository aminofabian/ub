-- Partial salary advance repayment: track repaid portion per advance + payslip allocations.

ALTER TABLE salary_advances
  ADD COLUMN amount_repaid DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER amount;

CREATE TABLE salary_advance_repayments (
  id              CHAR(36)      PRIMARY KEY,
  business_id     CHAR(36)      NOT NULL,
  advance_id      CHAR(36)      NOT NULL,
  payslip_id      CHAR(36)      NOT NULL,
  amount          DECIMAL(14,2) NOT NULL,
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_advance_repayments_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_advance_repayments_advance FOREIGN KEY (advance_id) REFERENCES salary_advances (id),
  CONSTRAINT fk_advance_repayments_payslip FOREIGN KEY (payslip_id) REFERENCES payslips (id)
);

CREATE INDEX idx_advance_repayments_advance ON salary_advance_repayments (advance_id);
CREATE INDEX idx_advance_repayments_payslip ON salary_advance_repayments (payslip_id);
