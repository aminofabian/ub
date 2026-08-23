-- =============================================================================
-- V235 — Nightly restock digest: ops-alert toggle + in-app notification template.
-- MySQL-compatible idempotent migration.
-- =============================================================================

-- 1. Ops-alert per-type toggle (default on, matching the panel convention;
--    the master `enabled` switch still gates delivery until a phone is verified).
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_ops_alert_settings'
     AND COLUMN_NAME = 'alert_restock_digest') = 0,
  "ALTER TABLE business_ops_alert_settings ADD COLUMN alert_restock_digest TINYINT(1) NOT NULL DEFAULT 1",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Platform notification template for the in-app digest (business-agnostic row).
-- Vars: {{branchName}} {{lineCount}} {{estTotal}} {{currency}} {{supplierCount}} {{runId}}
INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000030', NULL, 'inventory.restock_digest', 'en', 1,
   'Tonight''s list — {{branchName}}',
   '{{lineCount}} items · ~{{currency}} {{estTotal}} · {{supplierCount}} suppliers. Review and order before closing.',
   '/inventory/restock-digest/{{runId}}', 'OPERATIONAL', 'inventory', '["IN_APP"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  active = TRUE;
