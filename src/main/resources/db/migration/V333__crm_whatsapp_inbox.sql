-- M1 — CRM WhatsApp inbox (contact + conversation + message + raw envelope + send outbox).
--
-- Tables needed for the inbound ingest path (docs/scopes/whatsapp-crm/SCOPE.md §6.3-§6.4):
-- a raw envelope for idempotency/replay, the chat identity, the thread, the message, and
-- the outbound send outbox. Column names/types match the zelisline.ub.crm.* entities so
-- spring.jpa.hibernate.ddl-auto=validate passes.
--
-- Dialect: MySQL (MariaDB desktop must also accept it). Style mirrors V250__meta_capi_events.sql.
-- Add-only; note the FK-less business_id on crm_webhook_event (unrouted rows have no shop yet).

CREATE TABLE crm_contact (
  id           CHAR(36)     PRIMARY KEY,
  business_id  CHAR(36)     NOT NULL,
  customer_id  CHAR(36)     NULL,
  phone_e164   VARCHAR(20)  NOT NULL,
  name         VARCHAR(160) NULL,
  tags_json    MEDIUMTEXT   NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_crm_contact_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_crm_contact_business_phone (business_id, phone_e164),
  KEY idx_crm_contact_customer (customer_id)
);

CREATE TABLE crm_conversation (
  id                    CHAR(36)    PRIMARY KEY,
  business_id           CHAR(36)    NOT NULL,
  contact_id            CHAR(36)    NOT NULL,
  status                VARCHAR(16) NOT NULL DEFAULT 'open',
  assigned_user_id      CHAR(36)    NULL,
  last_message_at       TIMESTAMP   NULL,
  unread_count          INT         NOT NULL DEFAULT 0,
  window_expires_at     TIMESTAMP   NULL,
  ai_autoreply_disabled BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_crm_conv_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_crm_conv_business_status (business_id, status, last_message_at),
  KEY idx_crm_conv_assignee (business_id, assigned_user_id),
  KEY idx_crm_conv_contact (contact_id)
);

CREATE TABLE crm_message (
  id               CHAR(36)     PRIMARY KEY,
  conversation_id  CHAR(36)     NOT NULL,
  business_id      CHAR(36)     NOT NULL,
  direction        VARCHAR(8)   NOT NULL,
  wa_message_id    VARCHAR(128) NULL,
  type             VARCHAR(24)  NOT NULL,
  body             MEDIUMTEXT   NULL,
  content_json     MEDIUMTEXT   NULL,
  status           VARCHAR(16)  NULL,
  failure_reason   VARCHAR(512) NULL,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_crm_msg_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_crm_message_wamid (wa_message_id),
  KEY idx_crm_message_conversation (conversation_id, created_at),
  KEY idx_crm_message_business (business_id, created_at)
);

CREATE TABLE crm_webhook_event (
  id              CHAR(36)     PRIMARY KEY,
  wamid           VARCHAR(128) NULL,
  phone_number_id VARCHAR(64)  NULL,
  business_id     CHAR(36)     NULL,
  raw_json        MEDIUMTEXT   NOT NULL,
  processed_at    TIMESTAMP    NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_crm_webhook_event_wamid (wamid),
  KEY idx_crm_webhook_event_unrouted (business_id, processed_at)
);

CREATE TABLE crm_outbound (
  id               CHAR(36)     PRIMARY KEY,
  business_id      CHAR(36)     NOT NULL,
  conversation_id  CHAR(36)     NULL,
  to_phone_e164    VARCHAR(20)  NOT NULL,
  payload_json     MEDIUMTEXT   NOT NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'pending',
  attempt_count    INT          NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMP    NULL,
  last_error       VARCHAR(512) NULL,
  idempotency_key  VARCHAR(128) NULL,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_crm_out_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_crm_outbound_idem (business_id, idempotency_key),
  KEY idx_crm_outbound_due (status, next_attempt_at)
);
