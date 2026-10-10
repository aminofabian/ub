-- Optimistic lock on web_order_shipments (WebOrderShipment.version).
-- V324 was edited after some databases had already applied it, and
-- repair-on-migrate only fixes the checksum. Those tables never received
-- the column, so Hibernate validate fails on boot.
-- Fresh installs already get version from V324; this is a no-op there.

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'web_order_shipments'
     AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE web_order_shipments ADD COLUMN version BIGINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
