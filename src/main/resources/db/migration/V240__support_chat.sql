-- Support chat between tenants and the platform team (super-admin).
--
-- One conversation per tenant (unique business_id). Messages are 1:1 between
-- the tenant's users and the platform. `*_last_read_at` drive unread badges
-- per side; `support_messages.read_at` drives per-message read receipts (✓✓).

CREATE TABLE support_conversations (
  id                   CHAR(36)       PRIMARY KEY,
  business_id          CHAR(36)       NOT NULL,
  status               VARCHAR(16)    NOT NULL DEFAULT 'OPEN',
  subject              VARCHAR(191)   NULL,
  created_by           CHAR(36)       NOT NULL,
  created_by_name      VARCHAR(191)   NULL,
  last_message_at      TIMESTAMP(3)   NULL,
  last_message_preview VARCHAR(500)   NULL,
  tenant_last_read_at  TIMESTAMP(3)   NULL,
  admin_last_read_at   TIMESTAMP(3)   NULL,
  created_at           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_support_conversations_business (business_id),
  INDEX idx_support_conversations_status (status, last_message_at)
) ENGINE = InnoDB;

CREATE TABLE support_messages (
  id              CHAR(36)    PRIMARY KEY,
  conversation_id CHAR(36)    NOT NULL,
  sender_type     VARCHAR(16) NOT NULL,
  sender_user_id  CHAR(36)    NOT NULL,
  sender_name     VARCHAR(191) NULL,
  body            TEXT        NOT NULL,
  read_at         TIMESTAMP(3) NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_support_messages_conversation (conversation_id, created_at)
) ENGINE = InnoDB;
