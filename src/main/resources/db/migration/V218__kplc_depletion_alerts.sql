-- Opt-in SMS/WhatsApp before estimated prepaid run-out.
ALTER TABLE customer_kplc_meters
  ADD COLUMN depletion_alerts_enabled TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN last_two_day_alert_on DATE NULL,
  ADD COLUMN last_one_day_alert_on DATE NULL;
