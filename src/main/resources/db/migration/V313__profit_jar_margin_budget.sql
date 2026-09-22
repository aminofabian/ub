-- Profit jar % (suggested pocket share) + daily margin budget (KES).

ALTER TABLE profit_pocket_settings
  ADD COLUMN profit_jar_pct DECIMAL(5, 2) NULL AFTER friday_reminder_enabled,
  ADD COLUMN margin_budget_daily DECIMAL(14, 2) NULL AFTER profit_jar_pct;
