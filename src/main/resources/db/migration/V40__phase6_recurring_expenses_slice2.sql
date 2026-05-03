-- Phase 6 Slice 2 — recurring expenses (PHASE_6_PLAN.md §Slice 2).

CREATE TABLE expense_schedules (
  id                         CHAR(36)      PRIMARY KEY,
  business_id                CHAR(36)      NOT NULL,
  branch_id                  CHAR(36)      NULL,
  name                       VARCHAR(255)  NOT NULL,
  category_type              VARCHAR(16)   NOT NULL, -- fixed | variable
  amount                     DECIMAL(14,2) NOT NULL,
  payment_method             VARCHAR(32)   NOT NULL, -- cash | mpesa_manual | bank
  include_in_cash_drawer     BOOLEAN       NOT NULL DEFAULT FALSE,
  receipt_s3_key             VARCHAR(500)  NULL,
  expense_ledger_account_id  CHAR(36)      NOT NULL,
  frequency                  VARCHAR(16)   NOT NULL, -- daily | weekly | monthly
  start_date                 DATE          NOT NULL,
  end_date                   DATE          NULL,
  active                     BOOLEAN       NOT NULL DEFAULT TRUE,
  last_generated_on          DATE          NULL,
  created_by                 CHAR(36)      NOT NULL,
  created_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_expense_sched_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_expense_sched_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
  CONSTRAINT fk_expense_sched_expense_account FOREIGN KEY (expense_ledger_account_id) REFERENCES ledger_accounts (id),
  CONSTRAINT fk_expense_sched_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX idx_expense_sched_business_active ON expense_schedules (business_id, active);
CREATE INDEX idx_expense_sched_business_start ON expense_schedules (business_id, start_date);

CREATE TABLE expense_schedule_occurrences (
  id                   CHAR(36)      PRIMARY KEY,
  schedule_id          CHAR(36)      NOT NULL,
  business_id          CHAR(36)      NOT NULL,
  occurrence_date      DATE          NOT NULL,
  status               VARCHAR(16)   NOT NULL DEFAULT 'posted', -- posted | failed
  expense_id           CHAR(36)      NULL,
  posted_at            TIMESTAMP     NULL,
  failure_reason       VARCHAR(1000) NULL,
  created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_expense_schedule_occ (schedule_id, occurrence_date),
  CONSTRAINT fk_expense_occ_schedule FOREIGN KEY (schedule_id) REFERENCES expense_schedules (id) ON DELETE CASCADE,
  CONSTRAINT fk_expense_occ_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_expense_occ_expense FOREIGN KEY (expense_id) REFERENCES expenses (id)
);

CREATE INDEX idx_expense_occ_business_date ON expense_schedule_occurrences (business_id, occurrence_date);

