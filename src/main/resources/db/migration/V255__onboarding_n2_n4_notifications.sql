-- N2 / N4 micro-nudge templates for merchant onboarding sequence.
INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000041', NULL, 'onboarding.close_shift', 'en', 1,
   'Remember to close tonight',
   'Nice — remember to close it tonight. Closing count → variance you can trust.',
   '/shifts', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000042', NULL, 'onboarding.web_order', 'en', 1,
   'Someone ordered online!',
   'Fulfil it from Web orders — same stock as the till.',
   '/storefront/web-orders', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  active = TRUE;
