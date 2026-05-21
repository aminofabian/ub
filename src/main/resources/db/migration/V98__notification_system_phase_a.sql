-- Phase A — notification orchestration: inbox metadata + platform templates.

ALTER TABLE notifications
  ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'operational' AFTER type,
  ADD COLUMN priority VARCHAR(8) NOT NULL DEFAULT 'MEDIUM' AFTER category;

CREATE INDEX idx_notifications_business_user_created
  ON notifications (business_id, user_id, created_at DESC);

CREATE TABLE notification_templates (
  id                  CHAR(36)       PRIMARY KEY,
  business_id         CHAR(36)       NULL,
  type                VARCHAR(64)    NOT NULL,
  locale              VARCHAR(8)     NOT NULL DEFAULT 'en',
  version             INT            NOT NULL DEFAULT 1,
  title_template      VARCHAR(255)   NOT NULL,
  body_template       TEXT           NOT NULL,
  action_url_template VARCHAR(512)   NULL,
  notification_class  VARCHAR(16)    NOT NULL,
  category            VARCHAR(32)    NOT NULL,
  default_channels    JSON           NOT NULL,
  active              BOOLEAN        NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_notification_templates (business_id, type, locale, version)
);

-- Platform defaults (business_id NULL). Variables: {{orderId}}, {{total}}, {{customerName}}, {{currency}}
INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000001', NULL, 'order.received', 'en', 1,
   'Order received', 'We got your order {{orderId}} for {{total}} {{currency}}.',
   '/shop/account', 'TRANSACTIONAL', 'orders', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000002', NULL, 'order.payment_received', 'en', 1,
   'Payment received', 'Payment for order {{orderId}} ({{total}} {{currency}}) was successful.',
   '/shop/account', 'TRANSACTIONAL', 'orders', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000003', NULL, 'storefront.order.placed', 'en', 1,
   'New web order', '{{customerName}} · {{total}} {{currency}} · Order {{orderId}}',
   '/business/storefront/orders', 'OPERATIONAL', 'orders', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000004', NULL, 'storefront.order.paid', 'en', 1,
   'Web order paid', '{{customerName}} · {{total}} {{currency}} · Order {{orderId}}',
   '/business/storefront/orders', 'OPERATIONAL', 'orders', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000005', NULL, 'credit_sale.reminder', 'en', 1,
   'Credit purchase', '{{body}}', '{{paymentUrl}}', 'TRANSACTIONAL', 'credits', '["IN_APP","SMS","WHATSAPP"]', TRUE);
