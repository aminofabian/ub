-- Cash Drawer Ledger — Phase 1 (CASH_DRAWER_LEDGER_SCOPE.md).
-- Immutable per-denomination movement log for shifts. Expected balances are the
-- opening count plus the movement projection, and must reconcile to
-- shifts.expected_closing_cash (money total).

CREATE TABLE cash_drawer_movements (
  id                CHAR(36)     PRIMARY KEY,
  shift_id          CHAR(36)     NOT NULL,
  event_type        VARCHAR(40)  NOT NULL COMMENT 'OPENING, OPENING_ADJUSTMENT, SALE_RECEIVED, SALE_CHANGE, SALE_ADJUST, VOID_REVERSAL, REFUND, DRAWOUT, DRAWOUT_REVERSAL, PAID_IN, PAID_OUT, SAFE_DROP, TILL_TRANSFER, MANUAL_ADJUSTMENT, MID_SHIFT_COUNT',
  reference_id      CHAR(36)     NOT NULL COMMENT 'sale / refund / drawout / expense / shift id',
  reference_type    VARCHAR(40)  NOT NULL COMMENT 'SALE, VOID, REFUND, DRAWOUT, EXPENSE, TRANSFER, ADJUSTMENT, SHIFT',
  denomination      INT          NOT NULL COMMENT 'Face value in KES: 1, 5, 10, 20, 40, 50, 100, 200, 500, 1000',
  denomination_type VARCHAR(10)  NOT NULL COMMENT 'NOTE or COIN',
  quantity_delta    INT          NOT NULL COMMENT 'Positive = in, negative = out',
  confidence        VARCHAR(20)  NOT NULL DEFAULT 'INFERRED' COMMENT 'CONFIRMED or INFERRED',
  performed_by      CHAR(36)     NULL,
  metadata          JSON         NULL,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cdm_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
  CONSTRAINT fk_cdm_performed_by FOREIGN KEY (performed_by) REFERENCES users (id)
);

CREATE INDEX idx_cdm_shift_denom ON cash_drawer_movements (shift_id, denomination, created_at);
CREATE INDEX idx_cdm_shift_event ON cash_drawer_movements (shift_id, event_type, created_at);

-- Idempotent replay (offline sync / lazy backfill must never double-count).
-- reference_id is NOT NULL so NULLs (which MariaDB treats as distinct) cannot
-- defeat the uniqueness guarantee.
CREATE UNIQUE INDEX uq_cdm_replay ON cash_drawer_movements
  (shift_id, reference_type, reference_id, event_type, denomination);
