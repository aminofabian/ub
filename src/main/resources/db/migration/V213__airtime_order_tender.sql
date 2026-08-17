-- How the shopper reimbursed the merchant for an airtime sale.
-- The Kiosk Pay wallet still funds Instalipa; this is the till tender only.
ALTER TABLE airtime_orders
  ADD COLUMN tender VARCHAR(16) NOT NULL DEFAULT 'CASH' AFTER channel;

ALTER TABLE airtime_orders
  ADD KEY idx_ao_customer (business_id, customer_id);
