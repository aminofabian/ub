-- Simple store-room register (not sellable catalog / inventory stock).

CREATE TABLE store_items (
  id              CHAR(36)       PRIMARY KEY,
  business_id     CHAR(36)       NOT NULL,
  name            VARCHAR(255)   NOT NULL,
  barcode         VARCHAR(191)   NULL,
  quantity        INT            NOT NULL DEFAULT 0,
  expiry_date     DATE           NULL,
  buying_price    DECIMAL(14, 2) NULL,
  created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_store_items_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE INDEX idx_store_items_business_name ON store_items (business_id, name);
CREATE UNIQUE INDEX uq_store_items_business_barcode
  ON store_items (business_id, barcode);
