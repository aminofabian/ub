-- Supplier payout phone KYC: OTP-verified MSISDN before automated Send Money.

ALTER TABLE suppliers
  ADD COLUMN payout_phone_verified_at TIMESTAMP(6) NULL;

CREATE TABLE supplier_payout_phone_verifications (
  id              CHAR(36)     NOT NULL PRIMARY KEY,
  business_id     CHAR(36)     NOT NULL,
  supplier_id     CHAR(36)     NOT NULL,
  phone           VARCHAR(32)  NOT NULL,
  code_hash       CHAR(64)     NOT NULL,
  expires_at      TIMESTAMP(6) NOT NULL,
  attempts        INT          NOT NULL DEFAULT 0,
  max_attempts    INT          NOT NULL DEFAULT 5,
  consumed_at     TIMESTAMP(6) NULL,
  verified_at     TIMESTAMP(6) NULL,
  created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_sent_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_spp_verif_open (business_id, supplier_id, phone, consumed_at, created_at),
  CONSTRAINT fk_spp_verif_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);
