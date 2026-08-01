-- Tenant (owner/admin) WhatsApp/SMS ops alerts: verified recipient + per-event toggles.

CREATE TABLE business_ops_alert_settings (
  business_id            CHAR(36)     PRIMARY KEY,
  enabled                TINYINT(1)   NOT NULL DEFAULT 0,
  phone                  VARCHAR(32)  NULL,
  phone_verified_at      TIMESTAMP    NULL,
  alert_web_order        TINYINT(1)   NOT NULL DEFAULT 1,
  alert_shift            TINYINT(1)   NOT NULL DEFAULT 1,
  alert_supply           TINYINT(1)   NOT NULL DEFAULT 1,
  alert_credit_payment   TINYINT(1)   NOT NULL DEFAULT 1,
  created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_business_ops_alert_settings_business
    FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE business_ops_alert_phone_verifications (
  id              CHAR(36)     PRIMARY KEY,
  business_id     CHAR(36)     NOT NULL,
  phone           VARCHAR(32)  NOT NULL,
  code_hash       VARCHAR(64)  NOT NULL,
  expires_at      TIMESTAMP    NOT NULL,
  attempts        INT          NOT NULL DEFAULT 0,
  max_attempts    INT          NOT NULL DEFAULT 5,
  consumed_at     TIMESTAMP    NULL,
  verified_at     TIMESTAMP    NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_sent_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ops_alert_phone_verifications_business
    FOREIGN KEY (business_id) REFERENCES businesses (id),
  INDEX idx_ops_alert_phone_verifications_biz_phone_created
    (business_id, phone, created_at)
);
