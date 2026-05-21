-- Phase D — preferences, subscriptions, promo/insight templates.

CREATE TABLE notification_preferences (
  id            CHAR(36)       PRIMARY KEY,
  business_id   CHAR(36)       NOT NULL,
  user_id       CHAR(36)       NOT NULL,
  category      VARCHAR(32)    NOT NULL,
  channel       VARCHAR(16)    NOT NULL,
  enabled       BOOLEAN        NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_notification_preferences (business_id, user_id, category, channel)
);

CREATE TABLE notification_quiet_hours (
  user_id                 CHAR(36)       PRIMARY KEY,
  business_id             CHAR(36)       NOT NULL,
  enabled                 BOOLEAN        NOT NULL DEFAULT FALSE,
  timezone                VARCHAR(64)    NOT NULL DEFAULT 'Africa/Nairobi',
  start_local             TIME           NOT NULL DEFAULT '22:00:00',
  end_local               TIME           NOT NULL DEFAULT '07:00:00',
  allow_high_priority     BOOLEAN        NOT NULL DEFAULT TRUE,
  promotional_enabled     BOOLEAN        NOT NULL DEFAULT TRUE,
  max_promotional_per_day INT            NOT NULL DEFAULT 3
);

CREATE TABLE notification_subscriptions (
  id            CHAR(36)       PRIMARY KEY,
  business_id   CHAR(36)       NOT NULL,
  user_id       CHAR(36)       NOT NULL,
  item_id       CHAR(36)       NOT NULL,
  kind          VARCHAR(32)    NOT NULL,
  active        BOOLEAN        NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_notification_subscriptions (business_id, user_id, item_id, kind)
);

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000008', NULL, 'catalog.back_in_stock', 'en', 1,
   'Back in stock', '{{itemName}} is available again.',
   '/shop', 'PROMOTIONAL', 'promo', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000009', NULL, 'promo.price_drop', 'en', 1,
   'Price drop', '{{itemName}} is now {{newPrice}} {{currency}} (was {{oldPrice}}).',
   '/shop', 'PROMOTIONAL', 'promo', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000010', NULL, 'engagement.win_back', 'en', 1,
   'We miss you', 'It has been a while — check out what is new at the store.',
   '/shop', 'PROMOTIONAL', 'engagement', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000011', NULL, 'insights.abandoned_cart', 'en', 1,
   'Abandoned carts', '{{cartCount}} carts with items were left inactive.',
   '/storefront/web-orders', 'OPERATIONAL', 'insights', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000012', NULL, 'insights.peak_hours', 'en', 1,
   'Peak sales window', 'Busiest hour yesterday: {{peakHour}} ({{revenue}} {{currency}}).',
   '/business/reports', 'OPERATIONAL', 'insights', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000013', NULL, 'insights.top_products', 'en', 1,
   'Top products', 'Best sellers (7d): {{topItems}}',
   '/business/reports', 'OPERATIONAL', 'insights', '["IN_APP"]', TRUE);
