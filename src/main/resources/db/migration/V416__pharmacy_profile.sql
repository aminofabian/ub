-- Pharmacy Phase 1c — pharmacy profile and staff credentials (ADR 0033).
--
--  * pharmacy_profiles: one row per business that runs a pharmacy. Premises licence details are
--    what the shop typed in; recording them here does not make a shop licensed.
--    controlled_sales_enabled lets a shop sell CONTROLLED medicines through the till before the
--    controlled-stock register exists (off by default).
--  * pharmacy_staff_credentials: a staff member's professional registration number and title,
--    shown on dispensing records. Stored as entered; not verified with any regulator.
--
-- New tables only.

CREATE TABLE IF NOT EXISTS pharmacy_profiles (
  business_id               CHAR(36)     NOT NULL PRIMARY KEY,
  premises_licence_no       VARCHAR(64)  NULL,
  licence_expires_on        DATE         NULL,
  superintendent_user_id    CHAR(36)     NULL,
  controlled_sales_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
  short_dated_days          INT          NOT NULL DEFAULT 90,
  licence_reminder_sent_for DATE         NULL,
  licence_reminder_stage    INT          NOT NULL DEFAULT 0,
  version                   BIGINT       NOT NULL DEFAULT 0,
  created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_pp_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS pharmacy_staff_credentials (
  user_id          CHAR(36)     NOT NULL PRIMARY KEY,
  business_id      CHAR(36)     NOT NULL,
  title            VARCHAR(64)  NULL,
  registration_no  VARCHAR(64)  NULL,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_psc_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_psc_business (business_id)
);
