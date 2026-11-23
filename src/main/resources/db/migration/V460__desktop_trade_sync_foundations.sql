-- Desktop supplier trade sync — foundations (DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md §S0).
--
-- Two inert additions, read by no code until the sync slices (S1+) land:
--   1. cloud_synced_at on every table the till will mirror, so a row copied down
--      from the cloud is never pushed back up (same marker as web_orders, V278).
--   2. desktop_trade_actions: the till's queue of what a supplier DID offline
--      (answered, dispatched, …). The till pushes these; the cloud applies each
--      once, by id, through the same service the web calls.
--
-- Idempotent for MySQL 8.4 / MariaDB, in the house PREPARE style (no DELIMITER):
-- each column add is guarded on information_schema, so a re-run or an existing
-- column is a no-op. TIMESTAMP(3) matches the millisecond markers used elsewhere.

-- ── 1. cloud_synced_at mirrors ───────────────────────────────────────────────
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_orders' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE sales_orders ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_order_lines' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE sales_order_lines ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trade_accounts' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE trade_accounts ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trade_account_prices' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE trade_account_prices ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_invoices' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE sales_invoices ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_receipts' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE sales_receipts ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_credit_notes' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE sales_credit_notes ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'grocery_invoices' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE grocery_invoices ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'marketplace_suppliers' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE marketplace_suppliers ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'supplier_milestones' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE supplier_milestones ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'supplier_contributions' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE supplier_contributions ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 2. The till's action queue ───────────────────────────────────────────────
-- Lives on the desktop till; the cloud never writes it (an empty table there is
-- harmless). One row per thing a supplier did, pushed up and applied by id so a
-- retry does nothing twice.
CREATE TABLE IF NOT EXISTS desktop_trade_actions (
  id              VARCHAR(36)   NOT NULL,
  business_id     VARCHAR(36)   NOT NULL,
  -- answer | dispatch | cancel | appeal | approve_account | record_payment
  -- | credit_note | invoice_on_account | page_edit | listing | payout | milestones_seen
  kind            VARCHAR(32)   NOT NULL,
  -- the order / account / invoice / passport the action is about
  target_id       VARCHAR(36)   NOT NULL,
  -- JSON the cloud service needs (e.g. accepted quantities per line); NULL when the kind carries none
  payload         TEXT          NULL,
  -- staff member who did it on the till, for the audit trail
  actor_user_id   VARCHAR(36)   NULL,
  made_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- set once the cloud has processed it (applied, or refused for good); NULL = still waiting
  pushed_at       TIMESTAMP(3)  NULL,
  -- applied | refused ; NULL until pushed
  outcome         VARCHAR(16)   NULL,
  -- the cloud's reason when refused, shown to the person ("already answered online")
  outcome_detail  VARCHAR(500)  NULL,
  attempts        INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_dta_pending (business_id, pushed_at),
  KEY idx_dta_target (target_id)
);
