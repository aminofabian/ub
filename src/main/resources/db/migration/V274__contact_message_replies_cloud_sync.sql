-- Desktop ⇄ cloud message-reply relay (docs/scopes/DESKTOP_MESSAGES_SCOPE.md §7.5).
--
-- The desktop SKU holds no messaging providers; a reply is queued locally
-- (outcome='queued') and flushed to the shop's online instance, which sends it
-- through the shop's configured providers and acknowledges it. `cloud_synced_at`
-- marks replies the cloud has acknowledged, mirroring the per-sale marker used
-- by the sales sync (sales.cloud_synced_at).

ALTER TABLE contact_message_replies
    ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL;

CREATE INDEX idx_cmr_cloud_sync ON contact_message_replies (cloud_synced_at);
