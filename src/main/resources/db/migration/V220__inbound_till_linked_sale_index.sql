-- Fast till-payer lookup from sales. Idempotent: a killed deploy can leave the
-- index in place without a Flyway success row; a plain ADD INDEX then fails startup.
SET @idx_exists := (
  SELECT COUNT(1)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'inbound_till_payments'
     AND index_name = 'idx_itp_linked_sale'
);
SET @sql := IF(
  @idx_exists = 0,
  'ALTER TABLE inbound_till_payments ADD INDEX idx_itp_linked_sale (business_id, linked_sale_id), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
