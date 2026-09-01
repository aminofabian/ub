-- Threaded replies inside support conversations.
ALTER TABLE support_messages
    ADD COLUMN reply_to_message_id VARCHAR(36) NULL;

CREATE INDEX idx_support_messages_reply_to
    ON support_messages (reply_to_message_id);
