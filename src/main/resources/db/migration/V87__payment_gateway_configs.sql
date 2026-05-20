CREATE TABLE payment_gateway_configs (
  id                          CHAR(36) PRIMARY KEY,
  business_id                 CHAR(36) NOT NULL,
  gateway_type                VARCHAR(32) NOT NULL,
  label                       VARCHAR(100) NOT NULL,
  status                      VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  is_default                  BOOLEAN NOT NULL DEFAULT FALSE,
  credentials_json            TEXT NULL,
  display_instructions_json   TEXT NULL,
  last_tested_at              TIMESTAMP NULL,
  test_error_json             TEXT NULL,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_pgc_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_pgc_business_gateway (business_id, gateway_type)
);

CREATE INDEX idx_pgc_business_status ON payment_gateway_configs (business_id, status);
CREATE INDEX idx_pgc_active ON payment_gateway_configs (business_id, gateway_type, status);
