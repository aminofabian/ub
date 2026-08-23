-- Phase 5 (V2): one-tap receipt links — a short-lived, single-use token per web
-- order. The raw token travels in the WhatsApp/SMS link; only its SHA-256 hash
-- is stored, so a leaked DB cannot replay links.
ALTER TABLE web_orders
  ADD COLUMN receipt_token_hash        VARCHAR(64),  -- sha256 hex of the raw token
  ADD COLUMN receipt_token_expires_at  TIMESTAMP,    -- minted + 15 min (ReceiptTokenService.TOKEN_TTL)
  ADD COLUMN receipt_token_consumed_at TIMESTAMP;    -- single-use marker
