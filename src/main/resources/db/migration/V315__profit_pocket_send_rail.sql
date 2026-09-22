-- Profit Pocket: choose Daraja (platform B2B) or KopoKopo Send Money.
ALTER TABLE profit_pocket_settings
    ADD COLUMN IF NOT EXISTS send_rail VARCHAR(16) NULL;

COMMENT ON COLUMN profit_pocket_settings.send_rail IS
    'daraja | kopokopo — outbound rail for pocket / test send';
