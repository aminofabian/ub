-- Campaign segmentation: branch targeting + extended recipient scopes.

ALTER TABLE notification_campaigns
  ADD COLUMN catalog_branch_id CHAR(36) NULL AFTER recipient_scope,
  ADD INDEX idx_notification_campaigns_branch (business_id, catalog_branch_id);
