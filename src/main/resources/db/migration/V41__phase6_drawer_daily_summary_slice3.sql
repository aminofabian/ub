-- Phase 6 Slice 3 — cash drawer daily summary snapshot at shift close.

CREATE TABLE cash_drawer_daily_summaries (
  id                           CHAR(36)      PRIMARY KEY,
  business_id                  CHAR(36)      NOT NULL,
  branch_id                    CHAR(36)      NOT NULL,
  shift_id                     CHAR(36)      NOT NULL,
  business_date                DATE          NOT NULL,
  opening_cash                 DECIMAL(14,2) NOT NULL,
  cash_sales                   DECIMAL(14,2) NOT NULL DEFAULT 0,
  cash_refunds                 DECIMAL(14,2) NOT NULL DEFAULT 0,
  drawer_expenses              DECIMAL(14,2) NOT NULL DEFAULT 0,
  supplier_cash_from_drawer    DECIMAL(14,2) NOT NULL DEFAULT 0,
  expected_closing_cash        DECIMAL(14,2) NOT NULL,
  counted_closing_cash         DECIMAL(14,2) NULL,
  closing_variance             DECIMAL(14,2) NULL,
  snapshot_json                JSON          NULL,
  created_at                   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_cash_drawer_summary_shift (shift_id),
  CONSTRAINT fk_cash_drawer_summary_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_cash_drawer_summary_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
  CONSTRAINT fk_cash_drawer_summary_shift FOREIGN KEY (shift_id) REFERENCES shifts(id)
);

CREATE INDEX idx_cash_drawer_summary_business_date
  ON cash_drawer_daily_summaries (business_id, business_date);

