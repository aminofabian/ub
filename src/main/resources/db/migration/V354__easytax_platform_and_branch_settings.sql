-- EasyTax split: platform owns the environment URLs + availability, the tenant owns
-- opt-in/credentials/TIN, and each branch owns its KRA branch id + device serial.

CREATE TABLE IF NOT EXISTS platform_easytax_settings (
  id                   CHAR(36) NOT NULL PRIMARY KEY,
  enabled              BOOLEAN NOT NULL DEFAULT FALSE,
  sandbox_base_url     VARCHAR(255) NULL,
  production_base_url  VARCHAR(255) NULL,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO platform_easytax_settings (id, enabled) VALUES
  ('00000000-0000-0000-0000-0000000000e1', FALSE);

CREATE TABLE IF NOT EXISTS easytax_branch_settings (
  branch_id      CHAR(36) NOT NULL PRIMARY KEY,
  kra_branch_id  VARCHAR(32) NULL,
  device_serial  VARCHAR(64) NULL,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_easytax_branch FOREIGN KEY (branch_id) REFERENCES branches (id)
);

-- Base URL now comes from platform_easytax_settings; branch id + device serial are
-- per branch. Existing rows keep their credentials, TIN, and environment.
ALTER TABLE easytax_settings
  DROP COLUMN base_url,
  DROP COLUMN branch_id,
  DROP COLUMN device_serial;
