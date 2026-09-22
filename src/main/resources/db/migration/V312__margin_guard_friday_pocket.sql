-- Margin Guard mode (warn | approve | hard) + Friday Profit Pocket reminder.

ALTER TABLE profit_pocket_settings
  ADD COLUMN margin_guard_mode VARCHAR(16) NOT NULL DEFAULT 'warn' AFTER default_float,
  ADD COLUMN friday_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER margin_guard_mode;
