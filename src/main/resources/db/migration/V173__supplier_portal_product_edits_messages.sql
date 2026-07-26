-- Product edit approval queue + supplier↔shop message threads.

CREATE TABLE marketplace_supplier_product_edit_requests (
  id                       CHAR(36)     NOT NULL PRIMARY KEY,
  marketplace_supplier_id  CHAR(36)     NOT NULL,
  product_id               CHAR(36)     NOT NULL,
  requested_by_user_id     CHAR(36)     NULL,
  status                   VARCHAR(16)  NOT NULL DEFAULT 'pending',
  proposed_json            TEXT         NOT NULL,
  live_snapshot_json       TEXT         NULL,
  reviewed_by_user_id      CHAR(36)     NULL,
  reviewed_business_id     CHAR(36)     NULL,
  reviewed_at              TIMESTAMP(3) NULL,
  review_note              VARCHAR(1000) NULL,
  created_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_msp_edit_supplier
    FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers (id),
  CONSTRAINT fk_msp_edit_product
    FOREIGN KEY (product_id) REFERENCES marketplace_supplier_products (id),
  CONSTRAINT chk_msp_edit_status CHECK (status IN ('pending', 'approved', 'rejected'))
);

CREATE INDEX idx_msp_edit_pending
  ON marketplace_supplier_product_edit_requests (marketplace_supplier_id, status, created_at DESC);

CREATE INDEX idx_msp_edit_product_pending
  ON marketplace_supplier_product_edit_requests (product_id, status);

CREATE TABLE supplier_portal_messages (
  id                       CHAR(36)     NOT NULL PRIMARY KEY,
  marketplace_supplier_id  CHAR(36)     NOT NULL,
  business_id              CHAR(36)     NOT NULL,
  local_supplier_id        CHAR(36)     NULL,
  direction                VARCHAR(16)  NOT NULL,
  author_name              VARCHAR(120) NOT NULL,
  body                     VARCHAR(4000) NOT NULL,
  contact_message_id       CHAR(36)     NULL,
  read_at                  TIMESTAMP(3) NULL,
  created_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_spm_supplier
    FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers (id),
  CONSTRAINT fk_spm_business
    FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT chk_spm_direction CHECK (direction IN ('from_shop', 'from_supplier'))
);

CREATE INDEX idx_spm_supplier_created
  ON supplier_portal_messages (marketplace_supplier_id, created_at DESC);

CREATE INDEX idx_spm_business_created
  ON supplier_portal_messages (business_id, created_at DESC);
