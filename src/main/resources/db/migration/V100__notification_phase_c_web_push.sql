-- Phase C — Web Push device tokens + template channel updates.

CREATE TABLE device_tokens (
  id              CHAR(36)       PRIMARY KEY,
  business_id     CHAR(36)       NOT NULL,
  user_id         CHAR(36)       NOT NULL,
  platform        VARCHAR(16)    NOT NULL,
  token           VARCHAR(512)   NOT NULL,
  endpoint        VARCHAR(1024)  NULL,
  p256dh          VARCHAR(255)   NULL,
  auth_secret     VARCHAR(255)   NULL,
  user_agent      VARCHAR(512)   NULL,
  last_seen_at    TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  revoked_at      TIMESTAMP(3)   NULL,
  created_at      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_device_tokens_endpoint (endpoint(191)),
  INDEX idx_device_tokens_user (business_id, user_id, revoked_at)
);

UPDATE notification_templates
   SET default_channels = '["IN_APP","WEB_PUSH"]'
 WHERE business_id IS NULL
   AND type IN ('order.received', 'order.payment_received');

UPDATE notification_templates
   SET default_channels = '["IN_APP","WEB_PUSH"]'
 WHERE business_id IS NULL
   AND type IN ('storefront.order.placed', 'storefront.order.paid');
