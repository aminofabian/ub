-- V373 added appointments and queue priority, and left out the columns the same
-- PosDraft mapping already validates: a reminder flag, and money taken in advance
-- of a ticket (held, applied, credited, or forfeited). Without these the API
-- refuses to start (schema validation: missing column deposit_amount).
--
-- send_reminder defaults off so existing carts and tickets are unchanged.
-- deposit columns stay null until a shop actually takes money up front.
-- booking phone and deposit method stay out of logs.

ALTER TABLE pos_drafts
  ADD COLUMN send_reminder BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN reminder_sent_at TIMESTAMP NULL,
  ADD COLUMN deposit_amount DECIMAL(14,2) NULL,
  ADD COLUMN deposit_state VARCHAR(16) NULL,
  ADD COLUMN deposit_method VARCHAR(32) NULL,
  ADD COLUMN redo_of_sale_id CHAR(36) NULL;
