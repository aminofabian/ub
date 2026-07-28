-- Align payslips period columns with JPA int mapping (Hibernate validate).
-- V179 created SMALLINT/TINYINT; Java int expects INTEGER.

ALTER TABLE payslips
  MODIFY COLUMN period_year INT NOT NULL,
  MODIFY COLUMN period_month INT NOT NULL;
