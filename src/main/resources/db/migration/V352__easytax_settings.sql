-- Optional EasyTax (KRA eTIMS) integration, opt-in per business.
--
-- EasyTax is Pergamon Group's middleware in front of the KRA eTIMS VSCU. A shop
-- opts in and enters its device details; credentials are stored encrypted at rest
-- with the same AES-256-GCM key used for payment gateway credentials.

CREATE TABLE IF NOT EXISTS easytax_settings (
  business_id         CHAR(36) NOT NULL PRIMARY KEY,
  enabled             BOOLEAN NOT NULL DEFAULT FALSE,
  environment         VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  base_url            VARCHAR(255) NULL,
  username            VARCHAR(191) NULL,
  password_encrypted  TEXT NULL,
  tin                 VARCHAR(32) NULL,
  branch_id           VARCHAR(32) NULL,
  device_serial       VARCHAR(64) NULL,
  last_tested_at      TIMESTAMP NULL,
  last_test_ok        BOOLEAN NULL,
  last_test_message   VARCHAR(500) NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_easytax_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('a4b1c2d3-5e6f-4a7b-8c9d-0e1f2a3b4c5d', 'integrations.easytax.read',
   'View the EasyTax (KRA eTIMS) integration configuration.'),
  ('b5c2d3e4-6f7a-4b8c-9d0e-1f2a3b4c5d6e', 'integrations.easytax.write',
   'Enable, configure, and test the EasyTax (KRA eTIMS) integration.');

-- Owner: 22222222-0000-0000-0000-000000000001
-- Admin: 22222222-0000-0000-0000-000000000002
INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', 'a4b1c2d3-5e6f-4a7b-8c9d-0e1f2a3b4c5d'),
  ('22222222-0000-0000-0000-000000000001', 'b5c2d3e4-6f7a-4b8c-9d0e-1f2a3b4c5d6e'),
  ('22222222-0000-0000-0000-000000000002', 'a4b1c2d3-5e6f-4a7b-8c9d-0e1f2a3b4c5d'),
  ('22222222-0000-0000-0000-000000000002', 'b5c2d3e4-6f7a-4b8c-9d0e-1f2a3b4c5d6e');
