-- Mid-month join pay: full | half | prorate (default half).
-- Backfill from legacy prorate_join_month (true → prorate, false → full).

ALTER TABLE staff_profiles
  ADD COLUMN join_pay_mode VARCHAR(16) NOT NULL DEFAULT 'half';

UPDATE staff_profiles
SET join_pay_mode = CASE
  WHEN prorate_join_month = FALSE THEN 'full'
  ELSE 'prorate'
END;
