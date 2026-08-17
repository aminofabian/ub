-- Prepaid meter numbers a customer tab has asked us to remember.
CREATE TABLE customer_kplc_meters (
  id VARCHAR(36) NOT NULL,
  business_id VARCHAR(36) NOT NULL,
  customer_id VARCHAR(36) NOT NULL,
  meter_number VARCHAR(16) NOT NULL,
  last_used_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ckm_customer_meter (business_id, customer_id, meter_number),
  KEY idx_ckm_customer (business_id, customer_id, last_used_at)
);
