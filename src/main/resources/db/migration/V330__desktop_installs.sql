-- Kiosk Desktop install registry.
--
-- Every till that comes online checks in with its stable install id + Machine ID
-- so the Super Admin console can list installs and issue a machine-bound
-- activation key without the shop having to read out and send its Machine ID.
-- Rows are upserted by install_id on each check-in; last_seen_at is the presence
-- signal. auto_issued_at records an automatic issue for a paid, connected shop.

CREATE TABLE desktop_installs (
  install_id            VARCHAR(64)  NOT NULL PRIMARY KEY,
  machine_fingerprint   VARCHAR(64)  NOT NULL,
  cloud_business_id     CHAR(36)     NULL,
  business_name         VARCHAR(191) NOT NULL,
  contact_email         VARCHAR(255) NULL,
  app_version           VARCHAR(64)  NULL,
  platform              VARCHAR(64)  NULL,
  license_state         VARCHAR(32)  NULL,
  plan                  VARCHAR(32)  NULL,
  first_seen_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_ip               VARCHAR(64)  NULL,
  last_license_issue_id CHAR(36)     NULL,
  auto_issued_at        TIMESTAMP    NULL,
  INDEX idx_desktop_installs_last_seen (last_seen_at),
  INDEX idx_desktop_installs_fingerprint (machine_fingerprint),
  INDEX idx_desktop_installs_business (cloud_business_id)
);
