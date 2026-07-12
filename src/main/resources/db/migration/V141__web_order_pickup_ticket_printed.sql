-- One-time pickup-ticket print claim for cashier auto-print (idempotent across tills).

ALTER TABLE web_orders
  ADD COLUMN pickup_ticket_printed_at DATETIME(6) NULL AFTER notes;

-- Existing orders must never auto-print after deploy (backlog would otherwise reprint).
UPDATE web_orders
   SET pickup_ticket_printed_at = COALESCE(created_at, UTC_TIMESTAMP(6))
 WHERE pickup_ticket_printed_at IS NULL;
