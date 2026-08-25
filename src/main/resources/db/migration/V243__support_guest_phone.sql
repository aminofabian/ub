-- Guest identity: phone number on support_conversations.
--
-- The phone is the anchor that keeps one continuous thread per person: a
-- returning visitor (new browser, cleared storage, different device) who gives
-- the same phone resumes their existing conversation instead of fragmenting
-- into a fresh thread.

ALTER TABLE support_conversations
  ADD COLUMN guest_phone VARCHAR(32) NULL AFTER guest_name,
  ADD INDEX idx_support_conv_guest_phone (conversation_type, business_id, guest_phone);
