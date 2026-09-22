-- Profit Pocket: choose Daraja (platform B2B) or KopoKopo Send Money.
ALTER TABLE profit_pocket_settings
  ADD COLUMN send_rail VARCHAR(16) NULL AFTER margin_budget_daily;
