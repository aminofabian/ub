-- Idempotent support sends.
--
-- A client generates a stable id for the logical message and reuses it on
-- retry. The unique key is per conversation; legacy/system rows keep NULL
-- (MySQL allows multiple NULLs in a unique index).

ALTER TABLE support_messages
  ADD COLUMN client_message_id VARCHAR(64) NULL AFTER reply_to_message_id;

CREATE UNIQUE INDEX uq_support_messages_client
  ON support_messages (conversation_id, client_message_id);
