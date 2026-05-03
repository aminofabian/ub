-- Phase 4 Slice 1 — shifts & drawer baseline (PHASE_4_PLAN.md §Slice 1).
-- cash over/short GL: seeded per business via LedgerBootstrapService (3900).

CREATE TABLE shifts (
  id                     CHAR(36)     PRIMARY KEY,
  business_id            CHAR(36)     NOT NULL,
  branch_id              CHAR(36)     NOT NULL,
  opened_by              CHAR(36)     NOT NULL,
  status                 VARCHAR(16)  NOT NULL,
  opening_cash           DECIMAL(14, 2) NOT NULL,
  expected_closing_cash  DECIMAL(14, 2) NOT NULL,
  counted_closing_cash   DECIMAL(14, 2) NULL,
  closing_variance       DECIMAL(14, 2) NULL,
  opening_notes          VARCHAR(2000) NULL,
  closing_notes          VARCHAR(2000) NULL,
  closed_by              CHAR(36)     NULL,
  close_journal_entry_id CHAR(36)     NULL,
  opened_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at              TIMESTAMP    NULL,
  version                BIGINT       NOT NULL DEFAULT 0,
  created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_shifts_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_shifts_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
  CONSTRAINT fk_shifts_opened_by FOREIGN KEY (opened_by) REFERENCES users (id),
  CONSTRAINT fk_shifts_closed_by FOREIGN KEY (closed_by) REFERENCES users (id),
  CONSTRAINT fk_shifts_close_je FOREIGN KEY (close_journal_entry_id) REFERENCES journal_entries (id)
);

CREATE INDEX idx_shifts_business_branch_status ON shifts (business_id, branch_id, status);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000064', 'shifts.open',
   'Open a cash drawer shift at a branch.'),
  ('11111111-0000-0000-0000-000000000065', 'shifts.close',
   'Close the active shift and record drawer count.'),
  ('11111111-0000-0000-0000-000000000066', 'shifts.read',
   'View current or past shift state at a branch.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000064'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000065'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000066'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000064'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000065'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000066'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000064'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000065'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000066'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000064'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000065'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000066');
