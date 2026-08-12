-- M-Pesa till listening: keep the fallback poller cheap as the table grows, and let
-- each till own its own Buy Goods await. Both changes in one ALTER so the table is
-- rebuilt once.
--
-- idx_gsp_status_created: the poller scans pending rows across all businesses every
-- 30s. idx_gsp_pending starts with business_id, so that query had no usable index and
-- scanned the whole table.
--
-- await_owner_id: registering a till-await used to cancel every other open await in the
-- business, so a second till (or a re-registering cart) silently stopped the first till
-- from listening. Awaits are now replaced per owner (X-Till-Device-Id).
ALTER TABLE gateway_stk_pushes
  ADD INDEX idx_gsp_status_created (status, created_at),
  ADD COLUMN await_owner_id VARCHAR(64) NULL AFTER context_id;
