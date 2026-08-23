-- WhatsApp checkout Phase 3 — first-class channel (scope §10, D11)
ALTER TABLE web_orders
  ADD COLUMN channel            VARCHAR(24) NOT NULL DEFAULT 'WEB',   -- WEB | WHATSAPP | POS
  ADD COLUMN code               VARCHAR(24),                          -- canonical short code (D11)
  ADD COLUMN handoff_state      VARCHAR(24),                          -- opened | reopened | expired
  ADD COLUMN handoff_opened_at  TIMESTAMP,
  ADD COLUMN expires_at         TIMESTAMP;

-- Backfill canonical short codes for existing orders (scope D11)
UPDATE web_orders
  SET code = UPPER(RIGHT(REPLACE(id, '-', ''), 8))
  WHERE code IS NULL OR code = '';

-- Backfill the channel from the V1 notes marker (scope D5)
UPDATE web_orders
  SET channel = 'WHATSAPP'
  WHERE LOWER(notes) LIKE '%channel: whatsapp%';
