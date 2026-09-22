-- Profit Pocket Send Money tracking (KopoKopo outbound to expense destination).

ALTER TABLE profit_pockets
  ADD COLUMN send_money_status VARCHAR(32) NULL AFTER journal_entry_id,
  ADD COLUMN kopokopo_send_money_id VARCHAR(64) NULL AFTER send_money_status,
  ADD COLUMN payment_gateway_config_id CHAR(36) NULL AFTER kopokopo_send_money_id,
  ADD COLUMN send_money_message VARCHAR(500) NULL AFTER payment_gateway_config_id;

CREATE INDEX idx_profit_pockets_kopokopo_id ON profit_pockets (kopokopo_send_money_id);
