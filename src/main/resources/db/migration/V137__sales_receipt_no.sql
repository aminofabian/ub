-- Short sequential receipt number per business (1, 2, 3, ...) for humans;
-- UUID stays the primary key. Backfill existing sales oldest-first.

ALTER TABLE sales
  ADD COLUMN receipt_no BIGINT NULL AFTER business_id;

UPDATE sales s
JOIN (
  SELECT id,
         ROW_NUMBER() OVER (PARTITION BY business_id ORDER BY created_at, id) AS rn
  FROM sales
) numbered ON numbered.id = s.id
SET s.receipt_no = numbered.rn;

ALTER TABLE sales
  ADD UNIQUE KEY uq_sales_business_receipt_no (business_id, receipt_no);
