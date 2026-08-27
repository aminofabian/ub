-- Merchant onboarding message sequence (email + in-app).
-- M0 welcome remains in AuthRegistrationService; this tracks M1–W + fallbacks.

CREATE TABLE merchant_onboarding_enrollment (
  business_id        CHAR(36)       NOT NULL PRIMARY KEY,
  owner_user_id      CHAR(36)       NOT NULL,
  enrolled_at        DATETIME(6)    NOT NULL,
  muted_at           DATETIME(6)    NULL,
  completed_at       DATETIME(6)    NULL,
  first_sellable_at  DATETIME(6)    NULL,
  first_supply_at    DATETIME(6)    NULL,
  first_sale_at      DATETIME(6)    NULL,
  m4_email_due_at    DATETIME(6)    NULL,
  created_at         DATETIME(6)    NOT NULL,
  updated_at         DATETIME(6)    NOT NULL,
  CONSTRAINT fk_moe_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_moe_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
);

CREATE TABLE merchant_onboarding_send (
  id                 CHAR(36)       NOT NULL PRIMARY KEY,
  business_id        CHAR(36)       NOT NULL,
  step_key           VARCHAR(32)    NOT NULL,
  channel            VARCHAR(16)    NOT NULL,
  status             VARCHAR(16)    NOT NULL,
  skip_reason        VARCHAR(64)    NULL,
  dedupe_key         VARCHAR(128)   NOT NULL,
  sent_at            DATETIME(6)    NULL,
  created_at         DATETIME(6)    NOT NULL,
  UNIQUE KEY uq_mos_business_step_channel (business_id, step_key, channel),
  UNIQUE KEY uq_mos_dedupe (business_id, dedupe_key),
  KEY idx_mos_business_step (business_id, step_key),
  CONSTRAINT fk_mos_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000032', NULL, 'onboarding.fill_shelf', 'en', 1,
   'Fill your shelf in 10 minutes',
   'Start from Global catalog. Use families for sizes. Add manually only if it''s not in the shared catalog.',
   '/products/catalog', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000033', NULL, 'onboarding.sizes_right', 'en', 1,
   'Sizes done right',
   'Same drink, different sizes? One family, not three products.',
   '/products', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000034', NULL, 'onboarding.money_loop', 'en', 1,
   'Suppliers → supply → first sale',
   'Add a supplier, post a delivery so stock rises, open a shift, sell on Cashier.',
   '/suppliers', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000035', NULL, 'onboarding.first_sale', 'en', 1,
   'First sale 🎉',
   'Close tonight''s shift, connect M-Pesa when ready.',
   '/shifts', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000036', NULL, 'onboarding.go_live', 'en', 1,
   'Put your shop online',
   'Publish your storefront — same stock count as the till.',
   '/business', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000037', NULL, 'onboarding.team_rhythm', 'en', 1,
   'Team + rhythm',
   'Invite staff with PINs and let Kiosk run the weekly rhythm.',
   '/users', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000038', NULL, 'onboarding.week_checkin', 'en', 1,
   'Your week 1',
   'See what you built this week — and the next step.',
   '/business', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000039', NULL, 'onboarding.reengage', 'en', 1,
   '10 minutes gets you selling',
   'Your shelf is still empty. Start from Global catalog — or reply for free human help.',
   '/products/catalog', 'TRANSACTIONAL', 'engagement', '["IN_APP"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  active = TRUE;
