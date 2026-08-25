-- Visitor + storefront support chat.
--
-- support_conversations now carries three conversation types:
--   TENANT     tenant staff  <-> super-admin   (existing rows)
--   VISITOR    kiosk.ke guest <-> super-admin  (business_id = 'platform')
--   STOREFRONT storefront buyer <-> tenant staff (business_id = tenant id)
--
-- Guests are anonymous: a client-generated guest_id plus a server-minted
-- secret token (SHA-256 stored here). One thread per guest per (type, business).

ALTER TABLE support_conversations
  ADD COLUMN conversation_type VARCHAR(16) NOT NULL DEFAULT 'TENANT' AFTER business_id,
  ADD COLUMN guest_id VARCHAR(64) NULL AFTER conversation_type,
  ADD COLUMN guest_name VARCHAR(120) NULL AFTER guest_id,
  ADD COLUMN guest_token_hash VARCHAR(64) NULL AFTER guest_name,
  ADD COLUMN guest_last_read_at TIMESTAMP(3) NULL AFTER admin_last_read_at,
  ADD COLUMN tenant_thread_key CHAR(36) NULL AFTER guest_last_read_at,
  ADD UNIQUE KEY uq_support_conv_tenant_thread (tenant_thread_key),
  ADD UNIQUE KEY uq_support_conv_guest_thread (conversation_type, business_id, guest_id),
  ADD INDEX idx_support_conv_type_status (conversation_type, status, last_message_at);

-- Existing rows are all tenant threads — lock them to exactly one per business.
UPDATE support_conversations SET tenant_thread_key = business_id;
