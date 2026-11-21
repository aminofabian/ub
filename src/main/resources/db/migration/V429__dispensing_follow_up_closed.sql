-- Pharmacy follow-ups: a balance owed or a refill due can be closed.
--
-- Until now a part-supplied prescription stayed on the "still owed" list until a later sale
-- settled it, and a refill stayed on the "runs out" list until its window passed. A walk-in who
-- got the rest elsewhere, or a patient who was phoned and is not coming back, could not be
-- cleared. follow_up_closed_at says the line needs no more chasing, by whom, and why.
--
-- Additive, nullable, no backfill. Idempotent.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_record_lines' AND COLUMN_NAME = 'follow_up_closed_at') = 0,
  'ALTER TABLE dispensing_record_lines
     ADD COLUMN follow_up_closed_at TIMESTAMP NULL,
     ADD COLUMN follow_up_closed_by CHAR(36) NULL,
     ADD COLUMN follow_up_note VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
