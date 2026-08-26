-- Durable Meta Conversions API outbox + restricted delivery audit.
-- One row per CAPI event (CompleteRegistration / Purchase). request_json holds the
-- full Graph API body; the Authorization header is never stored — the encrypted
-- tenant access token (businesses.settings.metaCapi.accessTokenEnc) is decrypted
-- fresh at send time. (business_id, event_id) is unique so retries can never
-- double-send: the CAPI event_id and the browser Pixel eventID must match exactly.

CREATE TABLE meta_capi_events (
  id              CHAR(36) PRIMARY KEY,
  business_id     CHAR(36) NOT NULL,
  pixel_id        VARCHAR(64) NOT NULL,
  event_name      VARCHAR(64) NOT NULL,
  event_id        VARCHAR(128) NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'pending',
  http_status     INT NULL,
  request_json    MEDIUMTEXT NOT NULL,
  response_json   MEDIUMTEXT NULL,
  error           VARCHAR(1024) NULL,
  attempt_count   INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at         TIMESTAMP NULL,
  CONSTRAINT fk_mce_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_mce_business_event (business_id, event_id),
  KEY idx_mce_retry (status, attempt_count, created_at),
  KEY idx_mce_business_time (business_id, created_at)
);
