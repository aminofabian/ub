-- P0-1: every unpaid order now carries an expiry window so the stock sweeper can release
-- abandoned holds (previously only WhatsApp orders did, so a WEB shopper who abandoned at
-- payment held stock forever). Backfill existing orders that have no window.
--
-- `expires_at = created_at` marks them already due, so the next sweep releases them. Kept
-- date-function-free so it runs on MySQL/MariaDB and Postgres alike.
UPDATE web_orders
   SET expires_at = created_at
 WHERE status = 'pending_payment'
   AND expires_at IS NULL
   AND (fulfillment_status IS NULL OR fulfillment_status = 'awaiting_confirmation')
   AND (handoff_state IS NULL OR handoff_state <> 'expired');
