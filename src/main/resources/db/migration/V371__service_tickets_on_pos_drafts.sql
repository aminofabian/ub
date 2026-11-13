-- Walk-in service tickets (ADR 0014 amendment, ticket T14).
--
-- A walk-in ticket is a held cart with a chair and a start time, so it rides on the existing
-- held-cart tables instead of new ones: pos_drafts already carries the per-branch ticket
-- number, status, customer, shift, the resulting sale, a client id for idempotency, the cancel
-- reason and an optimistic version.
--
-- draft_kind separates the two uses. Every existing row is a CART, and the retail held-cart
-- list only reads CART rows, so tickets never appear among held carts.
-- pos_draft_lines.item_id becomes nullable because a service line has no item.

ALTER TABLE pos_drafts
  ADD COLUMN draft_kind VARCHAR(16) NOT NULL DEFAULT 'CART',
  ADD COLUMN service_stage VARCHAR(16) NULL,
  ADD COLUMN station_no INT NULL,
  ADD COLUMN requested_staff_profile_id CHAR(36) NULL,
  ADD COLUMN service_started_at TIMESTAMP NULL,
  ADD COLUMN service_finished_at TIMESTAMP NULL;

ALTER TABLE pos_draft_lines
  MODIFY COLUMN item_id CHAR(36) NULL,
  ADD COLUMN line_kind VARCHAR(16) NOT NULL DEFAULT 'ITEM',
  ADD COLUMN service_id CHAR(36) NULL,
  ADD COLUMN performer_staff_profile_id CHAR(36) NULL;
