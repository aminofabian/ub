-- Phase 2 — speed & trust.
--
-- 1. A store room can ask for approval before more than N leaves stock. The
--    threshold lives on the store room (not the business inventory settings) because
--    it is a back-room policy, and keeping it here avoids widening the shared
--    business-settings contract.
-- 2. A movement therefore has a state. Existing rows are APPLIED — they happened
--    before this column existed.

ALTER TABLE store_room_settings
  ADD COLUMN approval_threshold DECIMAL(14,4) NULL;   -- NULL = never ask

ALTER TABLE store_room_movements
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
  ADD COLUMN decided_by CHAR(36) NULL,
  ADD COLUMN decided_at TIMESTAMP NULL,
  ADD COLUMN decision_note VARCHAR(255) NULL;

CREATE INDEX idx_srm_business_status ON store_room_movements (business_id, status);
