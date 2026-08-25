-- Optional Cloudinary attachments on support chat messages (images, PDF, CSV, Excel, …).

ALTER TABLE support_messages
  ADD COLUMN attachment_url VARCHAR(1024) NULL AFTER body,
  ADD COLUMN attachment_public_id VARCHAR(512) NULL AFTER attachment_url,
  ADD COLUMN attachment_file_name VARCHAR(255) NULL AFTER attachment_public_id,
  ADD COLUMN attachment_content_type VARCHAR(128) NULL AFTER attachment_file_name,
  ADD COLUMN attachment_bytes BIGINT NULL AFTER attachment_content_type;
