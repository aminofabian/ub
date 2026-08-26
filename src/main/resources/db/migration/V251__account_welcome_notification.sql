-- Platform template for automatic welcome in-app notification on signup.
-- Vars: {{name}} {{businessName}}
INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000031', NULL, 'account.welcome', 'en', 1,
   'Welcome to Kiosk!',
   'Hi {{name}}, welcome aboard — we''re excited to have {{businessName}} on Kiosk. Setup, customization, and support are free. Call 0714 282 874 or email admin@kiosk.ke anytime.',
   '/business', 'TRANSACTIONAL', 'engagement', '["IN_APP","WEB_PUSH"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  notification_class = VALUES(notification_class),
  category = VALUES(category),
  default_channels = VALUES(default_channels),
  active = TRUE;
