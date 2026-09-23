-- Daily profit-pocketing log. Separate from owner-drawings journals:
-- a row snapshots the day's profit and the amount the owner logged as pocketed.
-- Cash pockets in profit_pockets stay the books; this table is the habit record.

ALTER TABLE profit_pockets
  ADD COLUMN note VARCHAR(500) NULL;

CREATE TABLE IF NOT EXISTS profit_pocket_days (
  id                CHAR(36) NOT NULL PRIMARY KEY,
  business_id       CHAR(36) NOT NULL,
  branch_key        CHAR(36) NOT NULL DEFAULT '',
  pocket_date       DATE NOT NULL,
  profit_amount     DECIMAL(14, 2) NOT NULL,
  pocketed_amount   DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  note              VARCHAR(500) NULL,
  status            VARCHAR(24) NOT NULL,
  skip_reason       VARCHAR(240) NULL,
  source            VARCHAR(16) NOT NULL DEFAULT 'manual',
  above_profit      BOOLEAN NOT NULL DEFAULT FALSE,
  revisions_json    TEXT NULL,
  created_by        CHAR(36) NOT NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_profit_pocket_day (business_id, branch_key, pocket_date),
  INDEX idx_profit_pocket_day_date (business_id, pocket_date)
);
