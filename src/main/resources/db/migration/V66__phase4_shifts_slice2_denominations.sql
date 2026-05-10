-- Phase 4 Slice 2 — shift denominations, audit trail, notes, expense tracking.
-- Adds denomination-level tracking for opening and closing cash counts per the
-- Kiosk POS Shifts Management Module specification.

-- Add new columns to existing shifts table
ALTER TABLE shifts
  ADD COLUMN variance_reason   VARCHAR(2000) NULL AFTER closing_notes,
  ADD COLUMN supervisor_id     CHAR(36)      NULL AFTER closed_by,
  ADD COLUMN reconciled_by     CHAR(36)      NULL AFTER supervisor_id,
  ADD COLUMN reconciled_at     TIMESTAMP     NULL AFTER closed_at,
  ADD COLUMN blind_closing     BOOLEAN       NOT NULL DEFAULT FALSE AFTER closing_variance;

-- Shift denominations: one row per denomination per count type (opening/closing)
CREATE TABLE shift_denominations (
  id                CHAR(36)     PRIMARY KEY,
  shift_id          CHAR(36)     NOT NULL,
  count_type        VARCHAR(10)  NOT NULL COMMENT 'OPENING or CLOSING',
  denomination      INT          NOT NULL COMMENT 'Face value in KES: 1, 5, 10, 20, 40, 50, 100, 200, 500, 1000',
  denomination_type VARCHAR(10)  NOT NULL COMMENT 'COIN or NOTE',
  quantity          INT          NOT NULL DEFAULT 0,
  total             DECIMAL(14, 2) NOT NULL DEFAULT 0.00 COMMENT 'denomination × quantity',
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_shift_denominations_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
  CONSTRAINT uq_shift_denomination UNIQUE (shift_id, count_type, denomination)
);

CREATE INDEX idx_shift_denominations_shift ON shift_denominations (shift_id, count_type);

-- Shift audit log: immutable chronological event log
CREATE TABLE shift_audit_logs (
  id             CHAR(36)     PRIMARY KEY,
  shift_id       CHAR(36)     NOT NULL,
  event_type     VARCHAR(40)  NOT NULL,
  performed_by   CHAR(36)     NULL,
  metadata       JSON         NULL,
  ip_address     VARCHAR(45)  NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_shift_audit_shift FOREIGN KEY (shift_id) REFERENCES shifts (id)
);

CREATE INDEX idx_shift_audit_shift ON shift_audit_logs (shift_id, created_at);

-- Shift expenses (Paid In / Paid Out / Safe Drop)
CREATE TABLE shift_expenses (
  id             CHAR(36)     PRIMARY KEY,
  shift_id       CHAR(36)     NOT NULL,
  type           VARCHAR(20)  NOT NULL COMMENT 'PAID_IN, PAID_OUT, SAFE_DROP',
  amount         DECIMAL(14, 2) NOT NULL,
  description    VARCHAR(500) NOT NULL,
  authorised_by  CHAR(36)     NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_shift_expenses_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
  CONSTRAINT fk_shift_expenses_authorised FOREIGN KEY (authorised_by) REFERENCES users (id)
);

CREATE INDEX idx_shift_expenses_shift ON shift_expenses (shift_id, type);

-- Add shift notes table for free-text notes
CREATE TABLE shift_notes (
  id             CHAR(36)     PRIMARY KEY,
  shift_id       CHAR(36)     NOT NULL,
  author_id      CHAR(36)     NOT NULL,
  note           TEXT         NOT NULL,
  note_type      VARCHAR(20)  NOT NULL COMMENT 'OPENING, CLOSING, RECONCILIATION, GENERAL',
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_shift_notes_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
  CONSTRAINT fk_shift_notes_author FOREIGN KEY (author_id) REFERENCES users (id)
);

CREATE INDEX idx_shift_notes_shift ON shift_notes (shift_id, note_type);
