-- ENP-1: branch-aware journal P&L / balance sheet.
-- Nullable branch_id on journal_entries; backfill from sales + expenses.
-- Branch-filtered reports include only rows with matching branch_id
-- (unallocated / null branch journals appear only in all-branches views).

ALTER TABLE journal_entries
  ADD COLUMN branch_id CHAR(36) NULL AFTER business_id;

CREATE INDEX idx_journal_entries_business_branch_date
  ON journal_entries (business_id, branch_id, entry_date);

-- Backfill from completed sales (revenue / COGS journals).
UPDATE journal_entries je
  INNER JOIN sales s ON s.id = je.source_id AND s.business_id = je.business_id
   SET je.branch_id = s.branch_id
 WHERE je.branch_id IS NULL
   AND je.source_type IN ('sale', 'sale_void', 'sale_refund', 'sale_payment_adjust')
   AND s.branch_id IS NOT NULL;

-- Backfill from expenses (OpEx journals).
UPDATE journal_entries je
  INNER JOIN expenses e ON e.id = je.source_id AND e.business_id = je.business_id
   SET je.branch_id = e.branch_id
 WHERE je.branch_id IS NULL
   AND je.source_type = 'expense'
   AND e.branch_id IS NOT NULL;

-- Shift close journals.
UPDATE journal_entries je
  INNER JOIN shifts sh ON sh.id = je.source_id AND sh.business_id = je.business_id
   SET je.branch_id = sh.branch_id
 WHERE je.branch_id IS NULL
   AND je.source_type = 'shift_close'
   AND sh.branch_id IS NOT NULL;
