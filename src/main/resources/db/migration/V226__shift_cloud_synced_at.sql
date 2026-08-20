-- Desktop sync: mark which closed shifts have been uploaded to the cloud
-- (DesktopSyncPushService). Unsynced shifts are pushed to the shop's online
-- instance; the timestamp is set only after the cloud acknowledges the batch,
-- so a failed/partial push is retried on the next sync run.

ALTER TABLE shifts
    ADD COLUMN cloud_synced_at DATETIME(6) NULL;
