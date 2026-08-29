-- SMS credits: 80%/100% usage email digest dedup marker.
-- NULL = never notified this cycle; otherwise the highest threshold already emailed
-- (80 or 100). Cleared by the monthly cycle reset.

ALTER TABLE business_sms_credit_accounts
  ADD COLUMN last_digest_pct INT NULL;
