-- Till-scoped cashier shifts: one open drawer per register (device key), with
-- legacy null-key rows remaining branch-shared until closed.

ALTER TABLE shifts
  ADD COLUMN till_device_key VARCHAR(64) NULL AFTER opened_by;

CREATE INDEX idx_shifts_business_branch_till_status
  ON shifts (business_id, branch_id, till_device_key, status);
