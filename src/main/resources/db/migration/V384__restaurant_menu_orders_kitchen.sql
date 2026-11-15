-- Restaurant operations, phase 1a (counter service) — ADR 0020, tickets R2–R7.
--
-- New tables: the menu (headings, dishes and products, modifier groups and options), kitchen
-- stations, and kitchen tickets. Existing tables get nullable columns only, which is a
-- metadata-only change on MySQL 8 and MariaDB 10.11 (the pattern V215 / V353 / V359 / V366+ used):
--   sale_items       menu_item_id, modifiers, line_note        (a DISH line)
--   pos_drafts       order_channel                             (a held ticket of kind ORDER)
--   pos_draft_lines  menu_item_id, modifiers, kitchen_note, station_id, sent_at, ready_at, served_at
--
-- A restaurant order is a pos_drafts row with draft_kind = 'ORDER'; draft_kind is a string
-- column, so the new kind needs no schema change.
--
-- Forward fix: set the business flag restaurant.enabled to false. Nothing here needs a DROP;
-- rows already written stay as history.

CREATE TABLE IF NOT EXISTS kitchen_stations (
  id           CHAR(36)    NOT NULL PRIMARY KEY,
  business_id  CHAR(36)    NOT NULL,
  name         VARCHAR(80) NOT NULL,
  sort_order   INT         NOT NULL DEFAULT 0,
  active       BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_kitchen_stations_business (business_id, sort_order),
  CONSTRAINT fk_kitchen_stations_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS menu_categories (
  id           CHAR(36)     NOT NULL PRIMARY KEY,
  business_id  CHAR(36)     NOT NULL,
  name         VARCHAR(120) NOT NULL,
  station_id   CHAR(36)     NULL,
  sort_order   INT          NOT NULL DEFAULT 0,
  active       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at   TIMESTAMP    NULL,
  KEY idx_menu_categories_business (business_id, deleted_at, sort_order),
  CONSTRAINT fk_menu_categories_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS menu_items (
  id           CHAR(36)      NOT NULL PRIMARY KEY,
  business_id  CHAR(36)      NOT NULL,
  category_id  CHAR(36)      NULL,
  name         VARCHAR(191)  NOT NULL,
  kind         VARCHAR(8)    NOT NULL,
  item_id      CHAR(36)      NULL,
  price        DECIMAL(14,2) NULL,
  tax_rate_id  CHAR(36)      NULL,
  station_id   CHAR(36)      NULL,
  available    BOOLEAN       NOT NULL DEFAULT TRUE,
  active       BOOLEAN       NOT NULL DEFAULT TRUE,
  sort_order   INT           NOT NULL DEFAULT 0,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at   TIMESTAMP     NULL,
  KEY idx_menu_items_business (business_id, deleted_at, sort_order),
  KEY idx_menu_items_business_item (business_id, item_id),
  CONSTRAINT fk_menu_items_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS modifier_groups (
  id           CHAR(36)     NOT NULL PRIMARY KEY,
  business_id  CHAR(36)     NOT NULL,
  name         VARCHAR(120) NOT NULL,
  min_select   INT          NOT NULL DEFAULT 0,
  max_select   INT          NOT NULL DEFAULT 1,
  sort_order   INT          NOT NULL DEFAULT 0,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at   TIMESTAMP    NULL,
  KEY idx_modifier_groups_business (business_id, deleted_at, sort_order),
  CONSTRAINT fk_modifier_groups_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS modifier_options (
  id           CHAR(36)      NOT NULL PRIMARY KEY,
  business_id  CHAR(36)      NOT NULL,
  group_id     CHAR(36)      NOT NULL,
  name         VARCHAR(120)  NOT NULL,
  price_delta  DECIMAL(14,2) NOT NULL DEFAULT 0,
  is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
  available    BOOLEAN       NOT NULL DEFAULT TRUE,
  sort_order   INT           NOT NULL DEFAULT 0,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at   TIMESTAMP     NULL,
  KEY idx_modifier_options_business_group (business_id, group_id, deleted_at, sort_order),
  CONSTRAINT fk_modifier_options_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_modifier_options_group FOREIGN KEY (group_id) REFERENCES modifier_groups (id)
);

CREATE TABLE IF NOT EXISTS menu_item_modifier_groups (
  id            CHAR(36)  NOT NULL PRIMARY KEY,
  business_id   CHAR(36)  NOT NULL,
  menu_item_id  CHAR(36)  NOT NULL,
  group_id      CHAR(36)  NOT NULL,
  sort_order    INT       NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_menu_item_modifier_groups (menu_item_id, group_id),
  KEY idx_menu_item_modifier_groups_business (business_id, menu_item_id, sort_order),
  KEY idx_menu_item_modifier_groups_group (business_id, group_id),
  CONSTRAINT fk_mimg_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_mimg_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id),
  CONSTRAINT fk_mimg_group FOREIGN KEY (group_id) REFERENCES modifier_groups (id)
);

-- One per station per send. Lines are a snapshot and are never updated; status is worked out
-- from the progress of the order lines they point at.
CREATE TABLE IF NOT EXISTS kitchen_tickets (
  id               CHAR(36)   NOT NULL PRIMARY KEY,
  business_id      CHAR(36)   NOT NULL,
  branch_id        CHAR(36)   NOT NULL,
  order_id         CHAR(36)   NOT NULL,
  order_number     BIGINT     NOT NULL,
  sale_id          CHAR(36)   NULL,
  station_id       CHAR(36)   NULL,
  round_no         INT        NOT NULL DEFAULT 1,
  ticket_type      VARCHAR(8) NOT NULL,
  status           VARCHAR(8) NOT NULL,
  sent_by          CHAR(36)   NOT NULL,
  sent_at          TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ready_at         TIMESTAMP  NULL,
  served_at        TIMESTAMP  NULL,
  acknowledged_at  TIMESTAMP  NULL,
  created_at       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_kitchen_tickets_board (business_id, branch_id, status, sent_at),
  KEY idx_kitchen_tickets_order (business_id, order_id),
  KEY idx_kitchen_tickets_sale (business_id, sale_id),
  CONSTRAINT fk_kitchen_tickets_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS kitchen_ticket_lines (
  id             CHAR(36)      NOT NULL PRIMARY KEY,
  business_id    CHAR(36)      NOT NULL,
  ticket_id      CHAR(36)      NOT NULL,
  order_line_id  CHAR(36)      NOT NULL,
  line_index     INT           NOT NULL,
  name           VARCHAR(191)  NOT NULL,
  quantity       DECIMAL(14,4) NOT NULL,
  modifiers      VARCHAR(2000) NULL,
  note           VARCHAR(255)  NULL,
  created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_kitchen_ticket_lines_ticket (business_id, ticket_id, line_index),
  KEY idx_kitchen_ticket_lines_order_line (business_id, order_line_id),
  CONSTRAINT fk_kitchen_ticket_lines_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_kitchen_ticket_lines_ticket FOREIGN KEY (ticket_id) REFERENCES kitchen_tickets (id)
);

ALTER TABLE sale_items
  ADD COLUMN menu_item_id CHAR(36)      NULL,
  ADD COLUMN modifiers    VARCHAR(2000) NULL,
  ADD COLUMN line_note    VARCHAR(255)  NULL;

ALTER TABLE pos_drafts
  ADD COLUMN order_channel VARCHAR(16) NULL;

ALTER TABLE pos_draft_lines
  ADD COLUMN menu_item_id CHAR(36)      NULL,
  ADD COLUMN modifiers    VARCHAR(2000) NULL,
  ADD COLUMN kitchen_note VARCHAR(255)  NULL,
  ADD COLUMN station_id   CHAR(36)      NULL,
  ADD COLUMN sent_at      TIMESTAMP     NULL,
  ADD COLUMN ready_at     TIMESTAMP     NULL,
  ADD COLUMN served_at    TIMESTAMP     NULL;

-- Permissions. INSERT IGNORE so a re-run after a partial failure does not die on rows already written.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f51', 'restaurant.menu.manage',
   'Create, edit and remove menu items, modifier groups, menu headings and kitchen stations.'),
  ('7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f52', 'restaurant.kitchen.update',
   'See kitchen tickets, mark dishes as being prepared or ready, and mark a dish as run out.');

-- A role for people who only work in the kitchen: they see what to make and nothing about money.
-- Not branch-locked, like service staff (V377).
INSERT IGNORE INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-00000000000b', NULL, 'kitchen_staff', 'Kitchen staff',
   'Sees kitchen tickets and marks dishes ready or run out. No till, no prices, no stock.',
   TRUE);

-- Owner 0001 and admin 0002 manage the menu. The kitchen permission also goes to the roles that
-- V377 gave "serve customers" rights to (0003, 0004, 0009) and to the new kitchen role.
INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f51'),
  ('22222222-0000-0000-0000-000000000002', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f51'),
  ('22222222-0000-0000-0000-000000000001', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f52'),
  ('22222222-0000-0000-0000-000000000002', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f52'),
  ('22222222-0000-0000-0000-000000000003', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f52'),
  ('22222222-0000-0000-0000-000000000004', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f52'),
  ('22222222-0000-0000-0000-000000000009', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f52'),
  ('22222222-0000-0000-0000-00000000000b', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f52');
