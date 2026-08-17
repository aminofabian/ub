-- Archived prepaid tokens from Kenya Power lookups, so monthly spend
-- survives after the upstream history window drops older slips.
CREATE TABLE customer_kplc_tokens (
  id VARCHAR(36) NOT NULL,
  business_id VARCHAR(36) NOT NULL,
  customer_id VARCHAR(36) NOT NULL,
  meter_number VARCHAR(16) NOT NULL,
  token_no VARCHAR(32) NOT NULL,
  purchased_at DATETIME(6) NULL,
  amount DECIMAL(12,2) NULL,
  units DECIMAL(12,4) NULL,
  receipt_no VARCHAR(64) NULL,
  payment_method VARCHAR(32) NULL,
  concepts_json JSON NULL,
  first_seen_at DATETIME(6) NOT NULL,
  last_seen_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ckt_customer_token (business_id, customer_id, token_no),
  KEY idx_ckt_meter_purchased (business_id, customer_id, meter_number, purchased_at)
);
