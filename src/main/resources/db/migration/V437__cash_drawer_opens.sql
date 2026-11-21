-- Each time a till's cash drawer opens: a pulse the till sent, or a key
-- turn the printer's drawer switch reported. opened_at is the till's clock.
-- closed_at is filled when that switch goes back to closed.

CREATE TABLE cash_drawer_opens (
  id                CHAR(36)     NOT NULL,
  business_id       CHAR(36)     NOT NULL,
  branch_id         CHAR(36)     NOT NULL,
  shift_id          CHAR(36)     NULL,
  sale_id           CHAR(36)     NULL,
  client_event_id   CHAR(36)     NOT NULL,
  opened_at         DATETIME(3)  NOT NULL,
  closed_at         DATETIME(3)  NULL,
  source            VARCHAR(16)  NOT NULL COMMENT 'KEY or TRIGGERED',
  reason            VARCHAR(16)  NOT NULL COMMENT 'KEY, CASH_SALE, REPRINT, UNKNOWN',
  sensor_confirmed  TINYINT(1)   NOT NULL DEFAULT 0,
  performed_by      CHAR(36)     NULL,
  till_device_key   VARCHAR(64)  NULL,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_cdo_client (business_id, client_event_id),
  KEY idx_cdo_shift (business_id, shift_id, opened_at),
  KEY idx_cdo_opened (business_id, opened_at),
  CONSTRAINT fk_cdo_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
  CONSTRAINT fk_cdo_performed_by FOREIGN KEY (performed_by) REFERENCES users (id)
);
