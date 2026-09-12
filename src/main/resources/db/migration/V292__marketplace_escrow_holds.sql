-- Marketplace escrow holds (Phase 3): obligation rows on Kiosk Pay custody.
-- Float stays in kiosk_pay_*; this table tracks supplier-bound holds only.

CREATE TABLE marketplace_escrow_holds (
  id                        CHAR(36)       NOT NULL PRIMARY KEY,
  business_id               CHAR(36)       NOT NULL,
  supplier_id               CHAR(36)       NOT NULL,
  marketplace_supplier_id   CHAR(36)       NULL,
  purchase_order_id         CHAR(36)       NULL,
  supplier_invoice_id       CHAR(36)       NULL,
  kiosk_pay_account_id      CHAR(36)       NOT NULL,
  amount                    DECIMAL(14, 2) NOT NULL,
  currency                  VARCHAR(8)     NOT NULL DEFAULT 'KES',
  status                    VARCHAR(24)    NOT NULL DEFAULT 'HELD',
  release_trigger           VARCHAR(24)    NULL,
  funded_ledger_reference   VARCHAR(128)   NULL,
  settle_disbursement_id    CHAR(36)       NULL,
  kopokopo_send_money_id    VARCHAR(128)   NULL,
  failure_reason            VARCHAR(512)   NULL,
  note                      VARCHAR(512)   NULL,
  version                   BIGINT         NOT NULL DEFAULT 0,
  created_at                TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at                TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  released_at               TIMESTAMP(6)   NULL,
  settled_at                TIMESTAMP(6)   NULL,
  CONSTRAINT fk_meh_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_meh_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
  CONSTRAINT fk_meh_kpa FOREIGN KEY (kiosk_pay_account_id) REFERENCES kiosk_pay_accounts (id),
  KEY idx_meh_business_status (business_id, status, created_at),
  KEY idx_meh_po (purchase_order_id),
  KEY idx_meh_invoice (supplier_invoice_id),
  KEY idx_meh_send_money (kopokopo_send_money_id),
  UNIQUE KEY uq_meh_funded_ref (funded_ledger_reference)
);
