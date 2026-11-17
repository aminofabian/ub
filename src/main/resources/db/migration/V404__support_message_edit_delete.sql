-- Message editing and soft-delete (unsend) for support chat.
-- Delete is a tombstone, never a row removal: reply-quotes and reaction rows
-- point at message ids, so the row must survive. Responses blank the body of a
-- deleted message; `deleted_at` is the tombstone marker.

ALTER TABLE support_messages
  ADD COLUMN edited_at  TIMESTAMP(3) NULL AFTER read_at,
  ADD COLUMN deleted_at TIMESTAMP(3) NULL AFTER edited_at;
