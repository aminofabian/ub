-- Service businesses, phases 1.5 to 3 (ADR 0015): prepaid packages, two staff sharing one
-- service line, products used per service, and online booking hours.
-- Reminder and deposit columns on pos_drafts are in V374.
--
-- Every change to an existing table is a nullable or defaulted ADD COLUMN, so rows written by
-- retail tills are untouched and the statements are metadata-only on MySQL 8 and MariaDB 10.11.
-- One new table, customer_service_packages: a customer's remaining prepaid sessions cannot live
-- in the wallet (which holds shillings, not sessions) — scope §15.

-- The new table comes first: if it cannot be created, nothing else in this file has run.
-- Sessions a customer has paid for and not yet used. The money is in deferred service revenue
-- (2330) until each session is used; value_remaining reaches zero with the last session.
CREATE TABLE IF NOT EXISTS customer_service_packages (
  id                  CHAR(36)      NOT NULL PRIMARY KEY,
  business_id         CHAR(36)      NOT NULL,
  branch_id           CHAR(36)      NOT NULL,
  customer_id         CHAR(36)      NOT NULL,
  service_id          CHAR(36)      NOT NULL,
  service_name        VARCHAR(191)  NOT NULL,
  sessions_total      INT           NOT NULL,
  sessions_remaining  INT           NOT NULL,
  price_paid          DECIMAL(14,2) NOT NULL,
  value_remaining     DECIMAL(14,2) NOT NULL,
  payment_method      VARCHAR(32)   NOT NULL,
  reference           VARCHAR(64)   NULL,
  sold_by             CHAR(36)      NOT NULL,
  expires_at          TIMESTAMP     NULL,
  idempotency_key     VARCHAR(80)   NOT NULL,
  version             BIGINT        NOT NULL DEFAULT 0,
  created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_customer_service_packages_key (business_id, idempotency_key),
  KEY idx_customer_service_packages_customer (business_id, customer_id, created_at),
  CONSTRAINT fk_customer_service_packages_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_customer_service_packages_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
  CONSTRAINT fk_customer_service_packages_service FOREIGN KEY (service_id) REFERENCES services (id)
);

-- V374 already added send_reminder, reminder_sent_at, the deposit columns, and redo_of_sale_id.
ALTER TABLE pos_drafts
  -- The prepaid package a session of which paid for this visit.
  ADD COLUMN redeemed_package_id CHAR(36) NULL,
  -- The reminder sweep looks across every business for bookings starting soon.
  ADD KEY idx_pos_drafts_scheduled_start (scheduled_start);

-- A second staff member sharing the work on one service line, with their share (1 to 99).
ALTER TABLE pos_draft_lines
  ADD COLUMN assistant_staff_profile_id CHAR(36) NULL,
  ADD COLUMN assistant_share_percent DECIMAL(5,2) NULL;

ALTER TABLE sale_items
  ADD COLUMN assistant_staff_profile_id CHAR(36) NULL,
  ADD COLUMN assistant_share_percent DECIMAL(5,2) NULL;

-- The prepaid package offered for a service (N sessions for one price), and the products the
-- service uses up each time as a JSON list of item id and quantity.
ALTER TABLE services
  ADD COLUMN package_sessions INT NULL,
  ADD COLUMN package_price DECIMAL(14,2) NULL,
  ADD COLUMN package_valid_days INT NULL,
  ADD COLUMN consumables_json VARCHAR(4000) NULL;

-- When customers may book online at a branch, as JSON; NULL means online booking is off.
ALTER TABLE branches
  ADD COLUMN booking_hours VARCHAR(600) NULL;
