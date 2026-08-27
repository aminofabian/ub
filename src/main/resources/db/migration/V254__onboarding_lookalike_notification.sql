-- N1 lookalike in-app template for merchant onboarding sequence.
INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000040', NULL, 'onboarding.lookalike', 'en', 1,
   'Sizes done right?',
   'These look like one family — group them so stock stays honest.',
   '/products', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  active = TRUE;
