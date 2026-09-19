-- Platform M-Pesa custody (till/paybill-only tenants): SA picks one rail.
-- Collect and settle MUST use the same provider (never Daraja+KopoKopo hybrid).

CREATE TABLE platform_mpesa_custody_settings (
  id                 CHAR(36)     NOT NULL PRIMARY KEY,
  custody_provider   VARCHAR(16)  NOT NULL DEFAULT 'OFF',
  updated_at         TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_mpesa_custody_settings (id, custody_provider, updated_at)
VALUES ('00000000-0000-0000-0000-000000000003', 'OFF', CURRENT_TIMESTAMP(6));

CREATE TABLE platform_custody_settlements (
  id                        CHAR(36)       NOT NULL PRIMARY KEY,
  business_id               CHAR(36)       NOT NULL,
  gateway_config_id         CHAR(36)       NOT NULL,
  stk_push_id               CHAR(36)       NOT NULL,
  provider                  VARCHAR(16)    NOT NULL,
  amount                    DECIMAL(14, 2) NOT NULL,
  currency                  VARCHAR(8)     NOT NULL DEFAULT 'KES',
  destination_type          VARCHAR(16)    NOT NULL,
  destination_till          VARCHAR(32)    NULL,
  destination_paybill       VARCHAR(32)    NULL,
  destination_account       VARCHAR(64)    NULL,
  status                    VARCHAR(24)    NOT NULL DEFAULT 'PENDING',
  disbursement_id           VARCHAR(128)   NULL,
  failure_reason            VARCHAR(512)   NULL,
  version                   BIGINT         NOT NULL DEFAULT 0,
  created_at                TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at                TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  settled_at                TIMESTAMP(6)   NULL,
  CONSTRAINT fk_pcs_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_pcs_config FOREIGN KEY (gateway_config_id) REFERENCES payment_gateway_configs (id),
  CONSTRAINT fk_pcs_stk FOREIGN KEY (stk_push_id) REFERENCES gateway_stk_pushes (id),
  UNIQUE KEY uq_pcs_stk_push (stk_push_id),
  KEY idx_pcs_business_status (business_id, status, created_at),
  KEY idx_pcs_disbursement (disbursement_id),
  KEY idx_pcs_provider_status (provider, status, created_at)
);
