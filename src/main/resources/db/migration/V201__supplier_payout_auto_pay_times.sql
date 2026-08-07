-- Per-tenant custom auto-pay clock times (JSON array of "HH:mm") + last-run slot guard.

ALTER TABLE supplier_payout_settings
  ADD COLUMN auto_pay_times_json VARCHAR(512) NULL AFTER auto_pay_enabled,
  ADD COLUMN auto_pay_last_run_slot VARCHAR(32) NULL AFTER auto_pay_times_json;

UPDATE supplier_payout_settings
SET auto_pay_times_json = '["00:00","18:00"]'
WHERE auto_pay_enabled = TRUE
  AND (auto_pay_times_json IS NULL OR auto_pay_times_json = '');
