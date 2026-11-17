-- Emoji reactions on support-chat messages (tenant↔platform, visitor, storefront).
-- One row per (message, actor, emoji); the unique key makes a toggle idempotent.
-- business_id is denormalised onto the row so reaction reads stay tenant-scoped
-- without a join back to the conversation.

CREATE TABLE support_message_reactions (
  id              CHAR(36)     PRIMARY KEY,
  message_id      CHAR(36)     NOT NULL,
  conversation_id CHAR(36)     NOT NULL,
  business_id     CHAR(36)     NULL,
  actor_type      VARCHAR(16)  NOT NULL,   -- TENANT | SUPER_ADMIN | GUEST
  actor_ref       VARCHAR(64)  NOT NULL,   -- userId (staff) or guestId
  emoji           VARCHAR(32)  NOT NULL,   -- a single unicode emoji cluster
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_support_reaction (message_id, actor_type, actor_ref, emoji),
  INDEX idx_support_reaction_msg (message_id),
  INDEX idx_support_reaction_conv (conversation_id)
);
