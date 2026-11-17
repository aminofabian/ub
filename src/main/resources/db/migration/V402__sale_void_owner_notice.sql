-- A voided sale tells the owner and admins, in the app and on the phone.

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000053', NULL, 'sale.voided', 'en', 1,
   'Sale voided',
   '{{voidedByName}} voided sale {{receiptLabel}} · {{currency}} {{amount}}{{reasonSuffix}}',
   '/business/logs', 'TRANSACTIONAL', 'sales',
   '["IN_APP","EMAIL","WEB_PUSH","SMS","WHATSAPP"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  default_channels = VALUES(default_channels),
  active = TRUE;
