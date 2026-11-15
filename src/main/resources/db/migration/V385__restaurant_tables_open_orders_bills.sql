-- Restaurant operations, phase 1b (dine-in, takeaway, delivery, bookings) — ADR 0020.
--
-- New tables: order_bills and order_bill_lines (one bill = one ordinary sale; an order paid by
-- three people has three). Existing tables get nullable columns only (metadata-only on MySQL 8
-- and MariaDB 10.11):
--   pos_drafts         table_resource_id, guest_count, served_by_staff_profile_id,
--                      bill_requested_at, delivery_address          (an order paid after it is eaten)
--   pos_draft_lines    seat_no, void_reason, voided_by, client_line_id
--   service_resources  capacity, area                               (a restaurant table is a resource of type TABLE)
--
-- Forward fix: set the business flag restaurant.enabled to false. Nothing here needs a DROP.

CREATE TABLE IF NOT EXISTS order_bills (
  id           CHAR(36)     NOT NULL PRIMARY KEY,
  business_id  CHAR(36)     NOT NULL,
  branch_id    CHAR(36)     NOT NULL,
  order_id     CHAR(36)     NOT NULL,
  bill_no      INT          NOT NULL,
  status       VARCHAR(8)   NOT NULL DEFAULT 'OPEN',
  sale_id      CHAR(36)     NULL,
  reason       VARCHAR(255) NULL,
  created_by   CHAR(36)     NOT NULL,
  closed_by    CHAR(36)     NULL,
  closed_at    TIMESTAMP    NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_order_bills_order_no (order_id, bill_no),
  KEY idx_order_bills_business_order (business_id, order_id),
  KEY idx_order_bills_business_sale (business_id, sale_id),
  CONSTRAINT fk_order_bills_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS order_bill_lines (
  id             CHAR(36)      NOT NULL PRIMARY KEY,
  business_id    CHAR(36)      NOT NULL,
  bill_id        CHAR(36)      NOT NULL,
  order_id       CHAR(36)      NOT NULL,
  order_line_id  CHAR(36)      NOT NULL,
  quantity       DECIMAL(14,4) NOT NULL,
  created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order_bill_lines_business_order (business_id, order_id),
  KEY idx_order_bill_lines_business_bill (business_id, bill_id),
  CONSTRAINT fk_order_bill_lines_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_order_bill_lines_bill FOREIGN KEY (bill_id) REFERENCES order_bills (id)
);

ALTER TABLE pos_drafts
  ADD COLUMN table_resource_id          CHAR(36)     NULL,
  ADD COLUMN guest_count                INT          NULL,
  ADD COLUMN served_by_staff_profile_id CHAR(36)     NULL,
  ADD COLUMN bill_requested_at          TIMESTAMP    NULL,
  ADD COLUMN delivery_address           VARCHAR(500) NULL;

ALTER TABLE pos_draft_lines
  ADD COLUMN seat_no        INT          NULL,
  ADD COLUMN void_reason    VARCHAR(255) NULL,
  ADD COLUMN voided_by      CHAR(36)     NULL,
  ADD COLUMN client_line_id VARCHAR(64)  NULL;

ALTER TABLE service_resources
  ADD COLUMN capacity INT         NULL,
  ADD COLUMN area     VARCHAR(60) NULL;

-- Permissions. INSERT IGNORE so a re-run after a partial failure does not die on rows already written.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f53', 'restaurant.orders.write',
   'Open an order at a table or to take away, add to it, send it to the kitchen and make its bill.'),
  ('7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f54', 'restaurant.orders.void_sent',
   'Cancel something on an order after it has been sent to the kitchen.'),
  ('7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f55', 'restaurant.bills.comp',
   'Close a bill with no payment: on the house, or the customer left without paying.');

-- A role for someone who takes orders at tables and does not handle the till.
INSERT IGNORE INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-00000000000c', NULL, 'waiter', 'Waiter',
   'Takes orders at tables, sends them to the kitchen and brings the bill. Does not take payment unless also given a till role.',
   TRUE);

-- Owner 0001 and admin 0002 get all three. Taking orders also goes to the roles V384 gave the
-- kitchen permission to (0003, 0004, 0009) and to the new waiter role.
INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f53'),
  ('22222222-0000-0000-0000-000000000002', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f53'),
  ('22222222-0000-0000-0000-000000000003', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f53'),
  ('22222222-0000-0000-0000-000000000004', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f53'),
  ('22222222-0000-0000-0000-000000000009', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f53'),
  ('22222222-0000-0000-0000-00000000000c', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f53'),
  ('22222222-0000-0000-0000-000000000001', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f54'),
  ('22222222-0000-0000-0000-000000000002', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f54'),
  ('22222222-0000-0000-0000-000000000001', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f55'),
  ('22222222-0000-0000-0000-000000000002', '7e2d9c3a-5b74-4f3c-9e86-9a4c3b2d8f55');
