-- Desktop sync: per-sale upload marker (realtime store-and-forward).
--
-- DesktopSyncPushService now tracks each sale's upload state instead of only
-- the shift's: a sale completes on the till -> the desktop pushes it to the
-- online shop immediately and stamps cloud_synced_at only after the cloud
-- acknowledges it. Null = still pending upload (offline, failed push, or a
-- sale made in the still-open shift).
--
-- The index keeps the periodic pending-sales scan cheap on a busy till.

ALTER TABLE sales
    ADD COLUMN cloud_synced_at DATETIME(6) NULL;

CREATE INDEX idx_sales_business_cloud_synced
    ON sales (business_id, cloud_synced_at);
