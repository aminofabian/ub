-- Phase 15 follow-up: new items should appear on the shop by default.
-- Existing rows were created with DEFAULT FALSE; opt in by default for catalog UX.
UPDATE items
SET web_published = TRUE
WHERE deleted_at IS NULL
  AND web_published = FALSE;

ALTER TABLE items
  MODIFY COLUMN web_published BOOLEAN NOT NULL DEFAULT TRUE;
