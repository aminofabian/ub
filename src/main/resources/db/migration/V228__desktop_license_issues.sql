-- Desktop license issuance history (Super Admin → Platform → Desktop licenses).
--
-- One row per license token issued from the console. The token is stored so a
-- "resend" can email the same artifact that was originally issued; expired
-- licenses are simply re-issued (new row) rather than edited.

CREATE TABLE desktop_license_issues (
  id                  CHAR(36)      PRIMARY KEY,
  business_name       VARCHAR(255)  NOT NULL,
  plan                VARCHAR(16)   NOT NULL,
  issued_at           TIMESTAMP(6)  NOT NULL,
  expires_at          TIMESTAMP(6)  NULL,
  machine_fingerprint VARCHAR(255)  NULL,
  recipient_email     VARCHAR(255)  NULL,
  email_sent          BOOLEAN       NOT NULL DEFAULT FALSE,
  token               VARCHAR(2048) NOT NULL,
  created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_desktop_license_issues_created (created_at),
  INDEX idx_desktop_license_issues_business (business_name)
);
