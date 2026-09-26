-- One-time KES 10 Airtime Float starter seed when a merchant first enables resale.

ALTER TABLE business_airtime_settings
  ADD COLUMN starter_seed_at TIMESTAMP NULL AFTER max_single_amount;
