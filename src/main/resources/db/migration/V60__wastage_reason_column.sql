-- Add a dedicated wastage_reason column for enum-based reporting.
-- Existing reason text is preserved; new rows populate both.

ALTER TABLE stock_movements
  ADD COLUMN wastage_reason VARCHAR(32) NULL
  AFTER reason;

-- Backfill existing wastage rows with a best-effort match
UPDATE stock_movements
   SET wastage_reason = 'OTHER'
 WHERE movement_type = 'wastage'
   AND wastage_reason IS NULL;

CREATE INDEX idx_sm_wastage_reason
    ON stock_movements (business_id, wastage_reason, created_at)
 WHERE movement_type = 'wastage';
