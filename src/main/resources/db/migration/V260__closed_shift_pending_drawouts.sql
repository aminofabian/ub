-- Closed shifts froze expected cash before pending drawouts left the till.
-- V259 only corrected open shifts. Apply the same till impact here, recompute
-- closing variance, and stamp applied_to_till so close cannot double-subtract.

ALTER TABLE cash_drawouts
  ADD COLUMN applied_to_till BOOLEAN NOT NULL DEFAULT FALSE AFTER expires_at;

-- Approved / voided rows already moved the till (void then restored).
UPDATE cash_drawouts
SET applied_to_till = TRUE
WHERE status IN ('APPROVED', 'VOIDED');

-- Open shifts: V259 already subtracted pending totals.
UPDATE cash_drawouts d
INNER JOIN shifts s ON s.id = d.shift_id
SET d.applied_to_till = TRUE
WHERE d.status = 'PENDING_APPROVAL'
  AND s.status = 'open';

UPDATE shifts s
INNER JOIN (
  SELECT shift_id, SUM(amount) AS pending_total
  FROM cash_drawouts
  WHERE status = 'PENDING_APPROVAL'
    AND applied_to_till = FALSE
  GROUP BY shift_id
) d ON d.shift_id = s.id
SET
  s.expected_closing_cash = s.expected_closing_cash - d.pending_total,
  s.closing_variance = CASE
    WHEN s.counted_closing_cash IS NULL THEN s.closing_variance
    ELSE s.counted_closing_cash - (s.expected_closing_cash - d.pending_total)
  END
WHERE s.status = 'closed';

UPDATE cash_drawouts d
INNER JOIN shifts s ON s.id = d.shift_id
SET d.applied_to_till = TRUE
WHERE d.status = 'PENDING_APPROVAL'
  AND s.status = 'closed'
  AND d.applied_to_till = FALSE;

UPDATE cash_drawer_daily_summaries cds
INNER JOIN shifts s ON s.id = cds.shift_id
SET
  cds.expected_closing_cash = s.expected_closing_cash,
  cds.closing_variance = s.closing_variance
WHERE s.status = 'closed';
