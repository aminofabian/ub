-- Supplier-side EasyTax (KRA eTIMS) opt-in.
--
-- A supplier fiscalises under its OWN TIN with its own EasyTax/VSCU, so this is
-- supplier-scoped (not tenant-scoped): one config per marketplace supplier, with its
-- single KRA branch id + device serial. The endpoint comes from the platform settings.

CREATE TABLE IF NOT EXISTS supplier_easytax_settings (
  supplier_id         CHAR(36) NOT NULL PRIMARY KEY,
  enabled             BOOLEAN NOT NULL DEFAULT FALSE,
  environment         VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  username            VARCHAR(191) NULL,
  password_encrypted  TEXT NULL,
  tin                 VARCHAR(32) NULL,
  kra_branch_id       VARCHAR(32) NULL,
  device_serial       VARCHAR(64) NULL,
  last_tested_at      TIMESTAMP NULL,
  last_test_ok        BOOLEAN NULL,
  last_test_message   VARCHAR(500) NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
