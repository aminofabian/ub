-- Drop the V240 one-thread-per-business unique key.
--
-- V240 declared UNIQUE (business_id) because there was exactly one thread per
-- business. V241 introduced conversation types where the same business_id
-- legitimately hosts several threads (VISITOR threads share the synthetic
-- "platform" id; STOREFRONT buyer threads share the tenant's id). Uniqueness
-- now lives on `tenant_thread_key` (TENANT threads) and
-- (conversation_type, business_id, guest_id) (guest threads). Keeping the old
-- key makes every second thread for a business fail with a
-- "Could not persist the requested change" 400.

SET @idx_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'support_conversations'
    AND index_name = 'uq_support_conversations_business'
);

SET @ddl = IF(
  @idx_exists > 0,
  'ALTER TABLE support_conversations DROP INDEX uq_support_conversations_business',
  'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
