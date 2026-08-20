-- Desktop sync (store-and-forward, DESKTOP_INSTALLATION.md §9): the till
-- re-hosts item photos from the cloud URL into its local media store. Keep the
-- originating cloud URL on the mirrored row so a later "Sync now" can detect a
-- changed photo and re-download it (HQ wins for master data). Null on the
-- cloud itself — only desktop mirrors write it.

ALTER TABLE item_images
  ADD COLUMN cloud_url VARCHAR(2048) NULL;
