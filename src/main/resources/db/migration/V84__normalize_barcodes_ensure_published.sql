-- V84: Normalise barcode column to match backend normalizeBarcode + frontend parseBarcode.
-- Strips spaces, hyphens, dots, and underscores; nullifies invalid codes.
-- Also re-applies the V83 web_published safety net in case that migration was missed.

-- 1. Normalise barcodes: strip separators first
UPDATE items
SET barcode = REPLACE(REPLACE(REPLACE(REPLACE(TRIM(barcode), ' ', ''), '-', ''), '.', ''), '_', '')
WHERE deleted_at IS NULL
  AND barcode IS NOT NULL
  AND barcode <> '';

-- 2. Nullify barcodes that are too short (< 4 chars) or contain non-digits after cleaning
UPDATE items
SET barcode = NULL
WHERE deleted_at IS NULL
  AND barcode IS NOT NULL
  AND (LENGTH(barcode) < 4 OR barcode REGEXP '[^0-9]');

-- 3. Safety net: ensure all undeleted items are web_published (re-run of V83 logic)
UPDATE items
SET web_published = TRUE
WHERE deleted_at IS NULL
  AND web_published = FALSE;
