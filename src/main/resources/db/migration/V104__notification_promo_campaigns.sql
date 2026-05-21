-- Promo / flash-sale campaigns (owner-scheduled buyer broadcasts).

CREATE TABLE notification_campaigns (
  id                  CHAR(36)       PRIMARY KEY,
  business_id         CHAR(36)       NOT NULL,
  name                VARCHAR(255)   NOT NULL,
  campaign_type       VARCHAR(32)    NOT NULL,
  status              VARCHAR(16)    NOT NULL DEFAULT 'DRAFT',
  title               VARCHAR(255)   NOT NULL,
  body                VARCHAR(2000)  NOT NULL,
  action_url          VARCHAR(512)   NULL,
  recipient_scope     VARCHAR(32)    NOT NULL DEFAULT 'ALL_BUYERS',
  scheduled_at        TIMESTAMP(3)   NULL,
  started_at          TIMESTAMP(3)   NULL,
  completed_at        TIMESTAMP(3)   NULL,
  recipients_targeted INT            NOT NULL DEFAULT 0,
  recipients_sent     INT            NOT NULL DEFAULT 0,
  created_by_user_id  CHAR(36)       NULL,
  created_at          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_notification_campaigns_schedule (status, scheduled_at),
  INDEX idx_notification_campaigns_business (business_id, created_at)
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000088', 'notifications.promotions.manage',
   'Create and send promotional notification campaigns');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, '11111111-0000-0000-0000-000000000088'
  FROM roles r
 WHERE r.role_key = 'owner' AND r.business_id IS NULL;

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000017', NULL, 'promo.flash_sale', 'en', 1,
   '{{title}}', '{{body}}', '{{actionUrl}}', 'PROMOTIONAL', 'promo', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000018', NULL, 'promo.weekly_deals', 'en', 1,
   '{{title}}', '{{body}}', '{{actionUrl}}', 'PROMOTIONAL', 'promo', '["IN_APP","WEB_PUSH"]', TRUE);
