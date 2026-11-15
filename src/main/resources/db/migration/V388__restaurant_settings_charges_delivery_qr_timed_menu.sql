-- Restaurant operations: the remaining pieces of phases 1b, 1.5, 2 and 3 — ADR 0020, amendment (3).
--
-- One new table, restaurant_settings (one row per business: service charge, pooled tips, packaging
-- charge, delivery fee, when the business day starts, whether customers may order from a table's
-- QR code). Everything else is nullable or defaulted columns (metadata-only on MySQL 8 and
-- MariaDB 10.11):
--   pos_drafts         delivery_fee, rider_name, dispatched_at, delivered_at      (a delivery order)
--   order_bills        service_charge, packaging_charge, delivery_fee             (what a bill added to its lines)
--   service_resources  qr_token                                                   (a table's QR code)
--   menu_items         serve_from_minute, serve_to_minute, happy_price,
--                      happy_from_minute, happy_to_minute, happy_days              (menus by time, happy hour)
--   recipe_lines       produced_item_id                                           (the recipe of a prepared batch)
--
-- Forward fix: nothing needs a DROP. Clearing a settings row's values switches each feature off.

CREATE TABLE IF NOT EXISTS restaurant_settings (
  business_id             CHAR(36)      NOT NULL PRIMARY KEY,
  service_charge_percent  DECIMAL(5,2)  NULL,
  tips_pooled             BOOLEAN       NOT NULL DEFAULT FALSE,
  packaging_charge        DECIMAL(14,2) NULL,
  delivery_fee            DECIMAL(14,2) NULL,
  day_starts_at_minute    INT           NOT NULL DEFAULT 0,
  qr_ordering             BOOLEAN       NOT NULL DEFAULT FALSE,
  qr_enabled_by           CHAR(36)      NULL,
  updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_restaurant_settings_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

ALTER TABLE pos_drafts
  ADD COLUMN delivery_fee  DECIMAL(14,2) NULL,
  ADD COLUMN rider_name    VARCHAR(120)  NULL,
  ADD COLUMN dispatched_at TIMESTAMP     NULL,
  ADD COLUMN delivered_at  TIMESTAMP     NULL;

ALTER TABLE order_bills
  ADD COLUMN service_charge   DECIMAL(14,2) NOT NULL DEFAULT 0,
  ADD COLUMN packaging_charge DECIMAL(14,2) NOT NULL DEFAULT 0,
  ADD COLUMN delivery_fee     DECIMAL(14,2) NOT NULL DEFAULT 0;

ALTER TABLE service_resources
  ADD COLUMN qr_token VARCHAR(64) NULL,
  ADD UNIQUE KEY uq_service_resources_qr_token (qr_token);

ALTER TABLE menu_items
  ADD COLUMN serve_from_minute INT           NULL,
  ADD COLUMN serve_to_minute   INT           NULL,
  ADD COLUMN happy_price       DECIMAL(14,2) NULL,
  ADD COLUMN happy_from_minute INT           NULL,
  ADD COLUMN happy_to_minute   INT           NULL,
  ADD COLUMN happy_days        VARCHAR(7)    NULL;

ALTER TABLE recipe_lines
  ADD COLUMN produced_item_id CHAR(36) NULL,
  ADD KEY idx_recipe_lines_business_produced (business_id, produced_item_id, sort_order);
