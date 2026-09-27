-- Short-lived Pickup Mtaani delivery quotes (scope §10). A quote ties a chosen
-- destination to a quoted fee so checkout cannot submit a cheaper amount than
-- we priced. The durable copy lives on the order/shipment; this row expires.

CREATE TABLE pickup_mtaani_quotes (
  id                 VARCHAR(36)   NOT NULL PRIMARY KEY,
  business_id        VARCHAR(36)   NOT NULL,
  mode               VARCHAR(16)   NOT NULL,
  origin_agent_id    BIGINT        NOT NULL,
  destination_id     BIGINT        NOT NULL,
  destination_label  VARCHAR(255)  NULL,
  upstream_fee_kes   DECIMAL(14,2) NOT NULL,
  shopper_fee_kes    DECIMAL(14,2) NOT NULL,
  fee_mode           VARCHAR(24)   NOT NULL,
  expires_at         TIMESTAMP(6)  NOT NULL,
  created_at         TIMESTAMP(6)  NOT NULL,
  INDEX idx_pm_quotes_business (business_id),
  INDEX idx_pm_quotes_expires (expires_at)
);
