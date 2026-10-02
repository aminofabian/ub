-- New tenants get credit/tab messaging on by default: a customer who takes items on
-- the tab should be told. Existing rows are deliberately NOT backfilled, so a tenant
-- who turned reminders off keeps that choice — flip individual shops in
-- Customers -> messaging if they want them on.
ALTER TABLE business_credit_settings
  MODIFY COLUMN credit_sale_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE;
